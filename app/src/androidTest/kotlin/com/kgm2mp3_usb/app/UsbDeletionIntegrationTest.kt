package com.kgm2mp3_usb.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses only UUID-prefixed fixtures on the emulator's explicitly supplied disposable public disk. */
@RunWith(AndroidJUnit4::class)
class UsbDeletionIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var storage: StorageRepository
    private lateinit var target: StorageTarget
    private lateinit var root: File
    private lateinit var work: File
    private lateinit var prefix: String

    @Before fun setup() {
        context = instrumentation.targetContext
        storage = StorageRepository(context)
        val id = InstrumentationRegistry.getArguments().getString("targetId")
            ?: throw AssertionError("Supply targetId for the disposable public test disk")
        target = storage.usbTargets().firstOrNull { it.id.equals(id, true) }
            ?: throw AssertionError("Disposable test disk $id is not mounted")
        root = File(target.rootPath ?: throw AssertionError("No direct test root"))
        assertTrue(root.isDirectory && root.canWrite())
        SettingsStore(context).usbTreeUri = null
        target = target.copy(treeUri = null)
        storage.selectUsb(target)
        prefix = "kgx-delete-test-${UUID.randomUUID()}"
        work = File(context.cacheDir, prefix).apply { mkdirs() }
        assertFalse(TransferController.busy)
    }
    @After fun cleanup() {
        UsbDeletionController.cancel()
        if (::root.isInitialized && ::prefix.isInitialized) {
            root.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach {
                if (it.isDirectory) it.deleteRecursively() else it.delete()
            }
        }
        if (::work.isInitialized) work.deleteRecursively()
    }

    @Test fun deletesOnlySelectedRootMusicAndProtectsPhoneAndSubfolders() {
        val selected = fixture("selected.mp3")
        val unselected = fixture("unselected.mp3")
        val phone = File(work, "phone.mp3").apply { writeText("phone original") }
        val nested = File(root, "$prefix-folder").apply { mkdir() }
        val nestedSong = File(nested, "nested.mp3").apply { writeText("nested original") }
        assertThrows(IOException::class.java) { storage.deleteUsbSong(target, song(phone)) }
        assertThrows(IOException::class.java) { storage.deleteUsbSong(target, song(nestedSong)) }
        storage.deleteUsbSong(target, song(selected))
        assertFalse(selected.exists())
        assertTrue(unselected.exists())
        assertEquals("phone original", phone.readText())
        assertEquals("nested original", nestedSong.readText())
    }

    @Test fun changedSnapshotAndCancelledUnlinkPreserveFiles() {
        val file = fixture("changed.mp3")
        val selected = song(file)
        assertTrue(storage.usbSnapshotUnchanged(target, selected))
        assertFalse(storage.usbSnapshotUnchanged(target, selected.copy(lastModified = selected.lastModified + 1)))
        file.appendText("changed")
        assertFalse(storage.usbSnapshotUnchanged(target, selected))
        assertThrows(IOException::class.java) { storage.deleteUsbSong(target, selected) }
        val unchanged = song(file)
        assertThrows(InterruptedIOException::class.java) {
            storage.deleteUsbSong(target, unchanged) { throw InterruptedIOException("cancelled") }
        }
        assertTrue(file.exists())
    }

    @Test fun controllerCancelsRemainingSongsAndDoesNotDuplicateOnListenerReattach() {
        val files = (1..25).map { fixture("cancel-$it.mp3") }
        val selected = files.map(::song)
        val done = CountDownLatch(1)
        val result = AtomicReference<UsbDeletionSnapshot>()
        val listener: (UsbDeletionSnapshot) -> Unit = { value ->
            if (!value.running && value.total == selected.size &&
                (value.deletedIds.any { it in selected.map { song -> song.id } } || value.failures.any { it.song.id == selected.first().id })) {
                result.set(value); done.countDown()
            }
        }
        UsbDeletionController.addListener(listener)
        try {
            instrumentation.runOnMainSync {
                UsbDeletionController.start(context, selected, target)
                UsbDeletionController.removeListener(listener)
                UsbDeletionController.addListener(listener) // Same job, a recreated view attaches.
                UsbDeletionController.cancel()
            }
            assertTrue("Deletion did not finish after cancellation", done.await(15, TimeUnit.SECONDS))
            val state = result.get()
            assertTrue(state.cancelled)
            assertEquals(selected.size, state.deleted + state.failures.size)
            assertEquals(state.deleted, files.count { !it.exists() })
            assertTrue(files.any { it.exists() })
            assertFalse(TransferController.busy)
        } finally { UsbDeletionController.removeListener(listener) }
    }

    @Test fun bothControllersRejectAnotherActiveOperationAndInterruptedDeleteNeverResumes() {
        val file = fixture("keep.mp3")
        val token = Any()
        assertTrue(UsbOperationGate.acquire(token))
        try {
            assertThrows(IllegalStateException::class.java) { UsbDeletionController.start(context, listOf(song(file)), target) }
            assertThrows(IllegalStateException::class.java) { TransferController.start(context, listOf(song(file)), target, OutputFormat.MP3) }
        } finally { UsbOperationGate.release(token) }
        context.getSharedPreferences("usb_deletion_state", Context.MODE_PRIVATE).edit()
            .putBoolean("active", true).putString("target", target.id).putInt("total", 1).putInt("deleted", 0).commit()
        UsbDeletionController.restoreInterrupted(context)
        assertTrue(UsbDeletionController.snapshot.interrupted)
        assertFalse(UsbDeletionController.snapshot.running)
        assertTrue(file.exists())
        assertFalse(context.getSharedPreferences("usb_deletion_state", Context.MODE_PRIVATE).getBoolean("active", false))
    }

    /**
     * Requires a real persisted root grant from DocumentsUI. The emulator's standard virtual
     * disk is SD-marked and its root is blocked. Android 12+ always enforces this restriction,
     * regardless of the RESTRICT_STORAGE_ACCESS_FRAMEWORK compatibility override.
     * The harness must supply a USB-marked root that the stock provider allows, or an already
     * valid real-provider root grant; an ordinary virtual SD root cannot prepare this grant.
     * This test never changes compatibility flags and never substitutes a child-directory URI.
     */
    @Test fun realSafGrantEnumeratesExactRootUriAndProtectsNestedFiles() {
        val selected = fixture("saf-selected.mp3")
        val unselected = fixture("saf-unselected.mp3")
        val nested = File(root, "$prefix-saf-folder").apply { mkdir() }
        val nestedFile = File(nested, "nested.mp3").apply { writeText("nested original") }
        val uri = ensureSafGrant()
        storage.setUsbTree(uri)
        val safTarget = storage.selectedUsb() ?: throw AssertionError("SAF target disappeared")
        assertEquals(uri.toString(), safTarget.treeUri)
        val rootDoc = DocumentFile.fromTreeUri(context, uri)!!
        val nestedDoc = rootDoc.findFile(nested.name)!!.findFile(nestedFile.name)!!
        val nestedSong = SongRef(nestedDoc.uri.toString(), nestedDoc.name!!, sourceUri = nestedDoc.uri.toString(),
            size = nestedDoc.length(), lastModified = nestedDoc.lastModified())
        assertThrows(IOException::class.java) { storage.deleteUsbSong(safTarget, nestedSong) }
        val scan = storage.scanUsb(safTarget)
        assertNull(scan.error)
        val song = scan.songs.first { it.name == selected.name }
        assertNotNull(song.sourceUri)
        assertTrue(storage.usbSnapshotUnchanged(safTarget, song))
        assertThrows(IOException::class.java) { storage.deleteUsbSong(safTarget, song.copy(lastModified = song.lastModified + 1)) }
        storage.deleteUsbSong(safTarget, song)
        assertFalse(selected.exists())
        assertTrue(unselected.exists())
        assertEquals("nested original", nestedFile.readText())
    }

    @Test fun actualUnmountBeforeUnlinkStopsDeletionAndRemountKeepsFile() {
        val file = fixture("unplug.mp3")
        val selected = song(file)
        val volume = shell("sm list-volumes public").lineSequence().map { it.trim().split(Regex("\\s+")) }
            .first { it.size >= 3 && it[2].equals(target.id, true) }[0]
        assertTrue(Regex("public:[0-9]+,[0-9]+").matches(volume))
        var checks = 0
        try {
            assertThrows(UsbTargetUnavailableException::class.java) {
                storage.deleteUsbSong(target, selected) {
                    checks++
                    if (checks == 2) {
                        shell("sm unmount $volume")
                        waitUntil("Test disk did not unmount", 10_000) { storage.usbTargets().none { it.id == target.id } }
                    }
                }
            }
        } finally {
            shell("sm mount $volume")
            waitUntil("Test disk did not remount", 15_000) { storage.usbTargets().any { it.id == target.id } && root.isDirectory }
        }
        assertTrue(file.exists())
        assertEquals("fixture unplug.mp3", file.readText())
    }

    private fun fixture(name: String) = File(root, "$prefix-$name").apply { writeText("fixture $name") }
    private fun song(file: File) = SongRef(file.absolutePath, file.name, path = file.absolutePath,
        size = file.length(), lastModified = file.lastModified())

    @Suppress("DEPRECATION")
    private fun ensureSafGrant(): Uri {
        val authority = "com.android.externalstorage.documents"
        val expectedRootId = "${target.id}:"
        fun grant(): Uri? = context.contentResolver.persistedUriPermissions.firstOrNull {
            it.uri.authority == authority && DocumentsContract.isTreeUri(it.uri) &&
                DocumentsContract.getTreeDocumentId(it.uri).equals(expectedRootId, true) &&
                it.isReadPermission && it.isWritePermission
        }?.uri
        grant()?.let { return it }
        val volume = context.getSystemService(StorageManager::class.java).storageVolumes.single {
            !it.isPrimary && it.isRemovable && it.uuid.equals(target.id, true)
        }
        val label = volume.getDescription(context)
        val automation = instrumentation.uiAutomation
        val originalInfo = automation.serviceInfo
        val originalFlags = originalInfo.flags
        originalInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = originalInfo
        var lastState = "DocumentsUI has not appeared"
        var confirmedTargetRoot = false
        try {
            val activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val intent = if (Build.VERSION.SDK_INT >= 29) volume.createOpenDocumentTreeIntent()
            else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri(authority, target.id))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            instrumentation.runOnMainSync { activity.startActivityForResult(intent, 101) }
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < deadline) {
                grant()?.let { return it }
                val node = automation.rootInActiveWindow
                if (node == null) { SystemClock.sleep(250); continue }
                lastState = describeTree(node)
                val allow = findNode(node) {
                    it.isEnabled && (it.text?.toString().equals("Allow", true) ||
                        it.text?.toString() in listOf("允许", "允許"))
                }
                if (allow != null) {
                    // Never approve an arbitrary picker result or an unrelated permission dialog.
                    val dialogNamesTarget = findNode(node) {
                        it.viewIdResourceName in listOf("android:id/message", "android:id/alertTitle") &&
                            it.text?.toString()?.contains(label, true) == true
                    } != null
                    assertTrue("SAF confirmation did not identify $label (${target.id}): $lastState",
                        confirmedTargetRoot && dialogNamesTarget)
                    clickNodeOrParent(allow)
                    SystemClock.sleep(250)
                    continue
                }
                val roots = findNode(node) { it.viewIdResourceName?.endsWith(":id/roots_list") == true }
                if (roots != null) {
                    val choice = findNode(roots) { it.text?.toString().equals(label, true) && it.isEnabled }
                    if (choice != null) {
                        assertTrue("Could not select exact test volume $label", clickNodeOrParent(choice))
                        confirmedTargetRoot = false
                    }
                    SystemClock.sleep(250)
                    continue
                }
                val toolbar = findNode(node) { it.viewIdResourceName?.endsWith(":id/toolbar") == true }
                val breadcrumb = findNode(node) { it.viewIdResourceName?.endsWith(":id/horizontal_breadcrumb") == true }
                val crumbs = treeNodes(breadcrumb).filter {
                    it.viewIdResourceName?.endsWith(":id/breadcrumb_text") == true
                }.map { it.text?.toString().orEmpty() }.toList()
                val correctTitle = findNode(toolbar) { it.text?.toString().equals(label, true) } != null
                // Exactly one breadcrumb rules out a same-volume subdirectory selection.
                val atExactRoot = correctTitle && crumbs.size == 1 && crumbs.single().equals(label, true)
                if (atExactRoot) {
                    confirmedTargetRoot = true
                    val useFolder = findNode(node) {
                        it.text?.toString().equals("Use this folder", true) ||
                            it.text?.toString() in listOf("使用此文件夹", "使用該資料夾") ||
                            it.viewIdResourceName?.endsWith(":id/action_menu_select") == true
                    }
                    if (useFolder != null && !useFolder.isEnabled) {
                        throw AssertionError("Android SAF forbids the exact test root $label (${target.id}). " +
                            "A reliable virtual SD root needs a real root grant prepared by the test harness; " +
                            "the test will not substitute a subdirectory. Picker: $lastState")
                    }
                    if (useFolder != null) clickNodeOrParent(useFolder)
                } else {
                    confirmedTargetRoot = false
                    // EXTRA_INITIAL_URI is a suggestion and may be ignored; navigate the explicit volume.
                    findNode(toolbar) {
                        it.isEnabled && it.isClickable && it.className?.toString() == "android.widget.ImageButton"
                    }?.let(::clickNodeOrParent)
                }
                SystemClock.sleep(250)
            }
            throw AssertionError("No exact persisted read/write SAF root grant for $label (${target.id}). Picker: $lastState")
        } finally {
            originalInfo.flags = originalFlags
            automation.serviceInfo = originalInfo
        }
    }

    private fun treeNodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node != null) {
            yield(node)
            for (i in 0 until node.childCount) yieldAll(treeNodes(node.getChild(i)))
        }
    }
    private fun findNode(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean) =
        treeNodes(node).firstOrNull(matches)

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.isClickable)
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }
    private fun describeTree(node: AccessibilityNodeInfo) = treeNodes(node).mapNotNull {
        val text = it.text?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        "${it.viewIdResourceName}:$text(enabled=${it.isEnabled})"
    }.joinToString(" | ").take(3000)
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun waitUntil(message: String, timeout: Long, check: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + timeout
        while (!check() && android.os.SystemClock.elapsedRealtime() < until) android.os.SystemClock.sleep(100)
        assertTrue(message, check())
    }
}
