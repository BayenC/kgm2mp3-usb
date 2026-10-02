package com.kgx2mp3.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Real mounted-volume integration; targetId must identify the emulator's disposable public disk. */
@RunWith(AndroidJUnit4::class)
class UsbIntegrationTest {
    private lateinit var context: Context
    private lateinit var storage: StorageRepository
    private lateinit var target: StorageTarget
    private lateinit var root: File
    private lateinit var work: File
    private lateinit var prefix: String
    private lateinit var existingScratch: Set<String>

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        storage = StorageRepository(context)
        val id = InstrumentationRegistry.getArguments().getString("targetId")
            ?: throw AssertionError("Supply targetId for a disposable mounted removable test volume")
        target = storage.usbTargets().firstOrNull { it.id.equals(id, true) }
            ?: throw AssertionError("Test removable volume $id is not mounted")
        assertNotNull("This integration run requires a directly writable removable root", target.rootPath)
        root = File(target.rootPath!!)
        assertTrue(root.isDirectory && root.canWrite())
        storage.selectUsb(target) // Explicit setup is allowed even without real USB transport in an emulator.
        assertEquals(target.id, storage.selectedUsb()?.id)
        prefix = "kgx-validation-${UUID.randomUUID()}"
        work = File(context.cacheDir, prefix).apply { mkdirs() }
        existingScratch = scratchFiles()
    }

    @After fun cleanup() {
        if (::root.isInitialized && ::prefix.isInitialized) {
            root.listFiles()?.filter { it.name.startsWith(prefix) && it.isFile }?.forEach { it.delete() }
        }
        if (::work.isInitialized) work.deleteRecursively()
    }

    @Test fun convertsCopiesAndOverwritesSameFilenameTransactionally() {
        val source = syntheticWav("$prefix.wav", seconds = 2)
        val firstSourceHash = sha256(source)
        transfer(source)
        val output = File(root, "$prefix.mp3")
        assertMp3(output, 2.0)
        assertEquals("First source must remain unchanged", firstSourceHash, sha256(source))
        assertTrue(storage.scanUsb(target).songs.any { it.name == output.name })
        val firstOutputHash = sha256(output)

        // Same leaf name deliberately produces a different song, exercising the overwrite path.
        syntheticWav(source.name, seconds = 4)
        val secondSourceHash = sha256(source)
        transfer(source)
        assertMp3(output, 4.0)
        assertNotEquals(firstOutputHash, sha256(output))
        assertEquals("Second source must remain unchanged", secondSourceHash, sha256(source))
        assertEquals(1, root.listFiles()!!.count { it.name.equals(output.name, true) })
        assertNoScratchOrJournal()
    }

    @Test fun cancellationDuringUsbCopyKeepsExistingSongAndCleansTemporary() {
        val original = syntheticWav("$prefix.wav", seconds = 2)
        transfer(original)
        val output = File(root, "$prefix.mp3")
        val previousHash = sha256(output)
        val previousLength = output.length()
        val replacement = syntheticWav("replacement.wav", seconds = 4)
        val local = TransferEngine(context, AtomicBoolean(false)) { _, _ -> }
            .prepareAudio(song(replacement), OutputFormat.MP3, File(work, "replacement-audio"))
        assertTrue("Need more than one copy chunk to cancel during streaming", local.length() > 65_536)
        val cancelled = AtomicBoolean(false)
        val writer = UsbWriter(context, target, {
            if (cancelled.get()) throw InterruptedIOException("test cancelled")
        }, { progress -> if (progress in 1..69) cancelled.set(true) })
        assertThrows(InterruptedIOException::class.java) { writer.write(local, output.name) }
        assertTrue(cancelled.get())
        assertEquals(previousLength, output.length())
        assertEquals("Cancellation must preserve the original USB song", previousHash, sha256(output))
        assertMp3(output, 2.0)
        assertNoScratchOrJournal()
    }

    private fun transfer(source: File) = TransferEngine(context, AtomicBoolean(false)) { _, _ -> }
        .transfer(song(source), target, OutputFormat.MP3)

    private fun song(file: File) = SongRef(file.absolutePath, file.name, path = file.absolutePath,
        size = file.length(), lastModified = file.lastModified())

    private fun assertMp3(file: File, seconds: Double) {
        assertTrue(file.isFile && file.length() > 0)
        val probe = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_format", "-show_streams", "-of", "json", file.absolutePath))
        assertTrue("Probe failed: ${probe.output}", ReturnCode.isSuccess(probe.returnCode))
        val json = JSONObject(probe.output)
        val audio = json.getJSONArray("streams").getJSONObject(0)
        assertEquals("mp3", audio.getString("codec_name"))
        assertEquals(seconds, json.getJSONObject("format").getString("duration").toDouble(), 0.5)
        val decode = FFmpegKit.executeWithArguments(arrayOf("-v", "error", "-xerror", "-err_detect", "explode",
            "-i", file.absolutePath, "-map", "0:a:0", "-f", "null", "-"))
        assertTrue("Strict playback failed: ${decode.output}", ReturnCode.isSuccess(decode.returnCode))
    }

    private fun scratchFiles() = root.listFiles()!!.filter { it.name.startsWith(".kgx-") }.map { it.name }.toSet()
    private fun assertNoScratchOrJournal() {
        assertTrue("USB temporary or backup files leaked", (scratchFiles() - existingScratch).isEmpty())
        val pending = context.getSharedPreferences("usb_transactions", Context.MODE_PRIVATE).getString("pending", "[]")
        assertEquals("USB transaction recovery journal was not cleared", "[]", pending)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun syntheticWav(name: String, seconds: Int): File {
        val rate = 44_100
        val byteCount = rate * seconds * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + byteCount).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(byteCount).array()
        val file = File(work, name)
        file.outputStream().buffered(65_536).use { output ->
            output.write(header)
            val oneSecond = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(rate) { i -> oneSecond.putShort((kotlin.math.sin(i * 2.0 * Math.PI * 440 / rate) * 8_000).toInt().toShort()) }
            repeat(seconds) { output.write(oneSecond.array()) }
        }
        return file
    }
}
