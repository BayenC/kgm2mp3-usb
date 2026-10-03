package com.kgm2mp3_usb.app

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal interface DestinationFile {
    val name: String
    val length: Long
    val isFile: Boolean
    fun input(): InputStream
    fun output(): OutputStream
    fun rename(name: String): Boolean
    fun delete(): Boolean
}
internal interface DestinationRoot {
    fun find(name: String): DestinationFile?
    fun create(name: String): DestinationFile
}
internal class FileRoot(private val root: File) : DestinationRoot {
    override fun find(name: String): DestinationFile? {
        val files = root.listFiles() ?: throw IOException("USB 已断开或无法读取。")
        return files.firstOrNull { it.name.equals(name, true) }?.let(::FileDestination)
    }
    override fun create(name: String): DestinationFile {
        val file = File(root, name)
        if (!file.createNewFile()) throw IOException("USB 临时文件创建失败。")
        return FileDestination(file)
    }
}
private class FileDestination(private var file: File) : DestinationFile {
    override val name get() = file.name
    override val length get() = file.length()
    override val isFile get() = file.isFile
    override fun input() = file.inputStream()
    override fun output() = file.outputStream()
    override fun rename(name: String): Boolean {
        val next = File(file.parentFile, name)
        if (next.exists()) return false
        if (!file.renameTo(next)) return false
        file = next
        return true
    }
    override fun delete() = file.delete()
}

/** Pure rollback policy, shared by recovery and the failure path and tested with real files. */
internal fun rollbackUsbFiles(directory: DestinationRoot, outputName: String, originalName: String?,
    temporaryName: String, backupName: String, preparing: Boolean): Boolean {
    if (preparing) return directory.find(temporaryName)?.let { it.delete() } ?: true
    val backup = directory.find(backupName)
    if (backup != null) {
        val final = directory.find(outputName)
        if (final != null && !final.delete()) return false
        if (!backup.rename(originalName ?: outputName)) return false
    } else if (originalName != null && directory.find(originalName) == null) return false
    else if (originalName == null && directory.find(outputName)?.delete() == false) return false
    return directory.find(temporaryName)?.let { it.delete() } ?: true
}
