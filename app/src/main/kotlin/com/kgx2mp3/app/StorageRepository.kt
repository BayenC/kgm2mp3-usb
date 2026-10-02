package com.kgx2mp3.app

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.kgx2mp3.core.KgmDecoder
import java.io.File
import java.io.InputStream

class StorageRepository(private val context: Context) {
    private val settings = SettingsStore(context)
    private val storage = context.getSystemService(StorageManager::class.java)

    fun scanPhone(): ScanResult = try {
        val tree = settings.sourceTreeUri
        if (tree != null) scanTree(Uri.parse(tree), phone = true)
        else {
            val directory = File(settings.downloadPath)
            if (!directory.isDirectory) ScanResult(emptyList(), "找不到酷狗下载文件夹，请在设置中选择下载目录。")
            else scanDirectory(directory, phone = true)
        }
    } catch (e: Exception) { ScanResult(emptyList(), "无法读取手机歌曲，请重新授权或选择下载目录。") }

    fun usbTargets(): List<StorageTarget> = storage.storageVolumes
        .filter { it.isRemovable && !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }
        .mapNotNull { volume ->
            val root = volumeRoot(volume)
            val id = volume.uuid ?: root?.name ?: return@mapNotNull null
            val savedTree = settings.usbTreeUri?.takeIf { sameVolume(it, id) }
            StorageTarget(id, volume.getDescription(context), root?.absolutePath, savedTree)
        }.distinctBy { it.id.uppercase() }

    /** A remembered choice wins; with several devices the user must explicitly choose. */
    fun selectedUsb(): StorageTarget? {
        val targets = usbTargets()
        val remembered = targets.firstOrNull {
            (settings.usbPath != null && it.rootPath == settings.usbPath) ||
                (settings.usbTreeUri != null && it.treeUri == settings.usbTreeUri)
        }
        return remembered ?: targets.singleOrNull()?.takeIf { usbMassStorageConnected() }
    }

    private fun usbMassStorageConnected(): Boolean = runCatching {
        context.getSystemService(UsbManager::class.java)?.deviceList?.values?.any { device ->
            device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE ||
                (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
        } == true
    }.getOrDefault(false)

    fun freeSpace(target: StorageTarget): Long? = runCatching {
        requireTarget(target).rootPath?.let { StatFs(it).availableBytes }
    }.getOrNull()

    fun selectUsb(target: StorageTarget) {
        val valid = requireTarget(target)
        settings.usbPath = valid.rootPath
        settings.usbTreeUri = valid.treeUri
    }

    fun scanUsb(target: StorageTarget): ScanResult = try {
        val valid = requireTarget(target)
        if (valid.treeUri != null) scanTree(Uri.parse(valid.treeUri), phone = false)
        else if (valid.rootPath != null) scanDirectory(File(valid.rootPath), phone = false)
        else ScanResult(emptyList(), "请先授权 USB 根目录。")
    } catch (e: IllegalArgumentException) {
        ScanResult(emptyList(), e.message ?: "请选择 USB 根目录。")
    } catch (e: Exception) { ScanResult(emptyList(), "无法读取 USB，请检查连接并重新授权根目录。") }

    fun setPhoneTree(uri: Uri) {
        val directory = DocumentFile.fromTreeUri(context, uri)
        require(directory != null && directory.isDirectory && directory.canRead()) { "所选文件夹无法读取。" }
        persistGrant(uri, writable = false)
        settings.sourceTreeUri = uri.toString()
    }

    fun setUsbTree(uri: Uri) {
        val volumeId = rootVolumeId(uri)
        val target = usbTargets().firstOrNull { it.id.equals(volumeId, ignoreCase = true) }
            ?: throw IllegalArgumentException("请选择已连接 USB 的根目录，不能选择手机存储或子文件夹。")
        val directory = DocumentFile.fromTreeUri(context, uri)
        require(directory != null && directory.isDirectory && directory.canRead() && directory.canWrite()) {
            "USB 根目录不可写，请重新授权。"
        }
        persistGrant(uri, writable = true)
        settings.usbTreeUri = uri.toString()
        settings.usbPath = target.rootPath
    }

    internal fun requireTarget(target: StorageTarget): StorageTarget {
        val active = usbTargets().firstOrNull { it.id.equals(target.id, ignoreCase = true) }
            ?: throw IllegalArgumentException("USB 已断开，请重新插入。")
        if (target.rootPath != null) require(
            active.rootPath != null && File(target.rootPath).canonicalPath == File(active.rootPath).canonicalPath
        ) { "目标必须是 USB 根目录。" }
        if (target.treeUri != null) require(rootVolumeId(Uri.parse(target.treeUri)).equals(active.id, true)) {
            "目标必须是 USB 根目录。"
        }
        return active.copy(treeUri = target.treeUri ?: active.treeUri)
    }

    internal fun openSong(song: SongRef): InputStream = when {
        song.sourceUri != null -> context.contentResolver.openInputStream(Uri.parse(song.sourceUri))
            ?: throw java.io.IOException("无法打开歌曲。")
        song.path != null -> File(song.path).inputStream()
        else -> throw java.io.IOException("歌曲位置已失效，请刷新列表。")
    }

    internal fun sourceUnchanged(song: SongRef): Boolean {
        if (song.path != null) {
            val file = File(song.path)
            return file.isFile && file.length() == song.size &&
                (song.lastModified == 0L || file.lastModified() == song.lastModified)
        }
        val doc = song.sourceUri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it)) } ?: return false
        return doc.exists() && doc.length() == song.size &&
            (song.lastModified == 0L || doc.lastModified() == song.lastModified)
    }

    private fun scanDirectory(directory: File, phone: Boolean): ScanResult {
        val files = directory.listFiles() ?: return ScanResult(emptyList(),
            if (phone) "请授予文件访问权限，或在设置中选择酷狗下载目录。" else "请授权 USB 根目录。")
        val now = System.currentTimeMillis()
        val songs = files.filter { it.isFile && stable(it.name, it.length(), it.lastModified(), now, phone) }
            .map { file ->
                val encrypted = FilenamePolicy.encryptedName(file.name) ||
                    (phone && runCatching { file.inputStream().use { KgmDecoder.isEncryptedHeader(it.readPrefix()) } }.getOrDefault(false))
                SongRef(file.absolutePath, file.name, path = file.absolutePath, size = file.length(),
                    lastModified = file.lastModified(), encrypted = encrypted)
            }.sortedBy { it.name.lowercase() }
        return ScanResult(songs)
    }

    private fun scanTree(uri: Uri, phone: Boolean): ScanResult {
        val directory = DocumentFile.fromTreeUri(context, uri)
        if (directory == null || !directory.exists() || !directory.canRead())
            return ScanResult(emptyList(), if (phone) "下载目录授权已失效，请重新选择。" else "USB 已断开或授权已失效。")
        val now = System.currentTimeMillis()
        val songs = directory.listFiles().filter { it.isFile && stable(it.name.orEmpty(), it.length(), it.lastModified(), now, phone) }
            .map { doc ->
                val name = doc.name.orEmpty()
                val encrypted = FilenamePolicy.encryptedName(name) || (phone && runCatching {
                    context.contentResolver.openInputStream(doc.uri)?.use { KgmDecoder.isEncryptedHeader(it.readPrefix()) }
                }.getOrDefault(false) == true)
                SongRef(doc.uri.toString(), name, sourceUri = doc.uri.toString(), size = doc.length(),
                    lastModified = doc.lastModified(), encrypted = encrypted)
            }.sortedBy { it.name.lowercase() }
        return ScanResult(songs)
    }

    private fun stable(name: String, length: Long, modified: Long, now: Long, phone: Boolean) =
        FilenamePolicy.musicName(name) && length > 0 && (!phone || modified <= 0 || now - modified >= 3_000)

    private fun persistGrant(uri: Uri, writable: Boolean) {
        context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
    }

    private fun volumeRoot(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return volume.directory
        // UUID mount paths are public removable-volume roots. No internal fallback is allowed.
        volume.uuid?.let { return File("/storage", it) }
        return runCatching { File(volume.javaClass.getMethod("getPath").invoke(volume) as String) }.getOrNull()
    }

    private fun sameVolume(tree: String, id: String) = runCatching {
        rootVolumeId(Uri.parse(tree)).equals(id, true)
    }.getOrDefault(false)

    private fun rootVolumeId(uri: Uri): String {
        require(uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(uri)) {
            "请选择系统文件选择器中的 USB 根目录。"
        }
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val id = documentId.substringBefore(':')
        require(documentId == "$id:" && !id.equals("primary", true) && id.isNotBlank()) {
            "请选择 USB 根目录，不能选择手机存储或子文件夹。"
        }
        return id
    }
}

internal fun InputStream.readPrefix(max: Int = 64): ByteArray {
    val bytes = ByteArray(max)
    var count = 0
    while (count < max) {
        val read = read(bytes, count, max - count)
        if (read < 0) break
        if (read == 0) continue
        count += read
    }
    return bytes.copyOf(count)
}
