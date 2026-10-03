package com.kgm2mp3_usb.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

internal class UsbTargetUnavailableException(message: String) : IOException(message)
internal class UsbAccessRevokedException(message: String) : IOException(message)

/** Pure filesystem boundary checks are shared by the deletion worker and JVM safety tests. */
internal object UsbDeletionPolicy {
    fun requireSnapshot(song: SongRef, name: String, size: Long, modified: Long, isFile: Boolean) {
        if (!isFile || !FilenamePolicy.musicName(name) || size <= 0)
            throw IOException("只能删除 USB 根目录中的普通音乐文件。")
        if (song.name != name || song.size != size || song.lastModified != modified)
            throw IOException("歌曲已经变化，请刷新后重新选择。")
    }

    fun requireDirectChild(root: File, song: SongRef): File {
        val path = song.path ?: throw IOException("歌曲位置无效，请重新选择。")
        if (song.sourceUri != null || song.id != path) throw IOException("歌曲位置无效，请重新选择。")
        val file = File(path)
        val rootCanonical = root.canonicalFile
        if (!file.isAbsolute || file.parentFile?.canonicalFile != rootCanonical ||
            file.canonicalFile.parentFile != rootCanonical || file.name != song.name)
            throw IOException("只能删除本次所选 USB 根目录中的歌曲。")
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attributes.isSymbolicLink) throw IOException("不能删除链接文件，请刷新歌曲列表。")
        requireSnapshot(song, file.name, attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.isRegularFile)
        return file
    }
}
