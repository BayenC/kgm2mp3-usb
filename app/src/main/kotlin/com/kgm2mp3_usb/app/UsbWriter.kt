package com.kgm2mp3_usb.app

import android.content.Context
import android.annotation.SuppressLint
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** USB transaction: verified temporary file, original backup, then rename with rollback. */
// USB recovery checkpoints must reach persistent storage before destructive rename steps.
@SuppressLint("ApplySharedPref")
internal class UsbWriter(
    private val context: Context,
    private val target: StorageTarget,
    private val checkCancelled: () -> Unit,
    private val onProgress: (Int) -> Unit,
) {
    private val storage = StorageRepository(context)
    private val journal = context.getSharedPreferences("usb_transactions", Context.MODE_PRIVATE)
    private val directory: DestinationRoot by lazy {
        val active = storage.requireTarget(target)
        val path = active.rootPath?.let(::File)
        if (path != null && path.isDirectory && path.canWrite() && path.listFiles() != null)
            FileRoot(path)
        else {
            val uri = active.treeUri ?: throw IOException("请在设置中授权 USB 根目录。")
            val doc = DocumentFile.fromTreeUri(context, Uri.parse(uri))
                ?: throw IOException("USB 授权已失效。")
            if (!doc.isDirectory || !doc.canWrite()) throw IOException("USB 不可写，请检查连接与授权。")
            DocumentRoot(context, doc)
        }
    }

    fun write(source: File, outputName: String) {
        storage.requireTarget(target)
        recover()
        checkCancelled()
        val available = storage.freeSpace(target)
        if (available != null && available < source.length() + 1_048_576)
            throw IOException("USB 剩余空间不足。")
        val token = UUID.randomUUID().toString()
        val temporaryName = ".kgx-$token.tmp"
        val backupName = ".kgx-$token.bak"
        var record: Transaction? = Transaction(token, target.id, outputName, null, temporaryName,
            backupName, source.length(), "", preparing = true)
        save(record!!)
        val temporary = directory.create(temporaryName)
        var backup: DestinationFile? = null
        var committed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val expectedLength = source.length()
            source.inputStream().use { input -> temporary.output().use { output ->
                copy(input, output, expectedLength, digest)
                output.flush()
                if (output is java.io.FileOutputStream) output.fd.sync()
            } }
            val expectedHash = digest.digest().hex()
            checkCancelled()
            verify(temporary, expectedLength, expectedHash)
            checkCancelled()
            storage.requireTarget(target)
            val existing = directory.find(outputName)
            if (existing != null && !existing.isFile) throw IOException("USB 中存在同名文件夹，无法覆盖。")
            record = Transaction(token, target.id, outputName, existing?.name, temporaryName,
                backupName, expectedLength, expectedHash, preparing = false)
            save(record)
            // Keep this short commit section cancellation-free so the old song can be restored.
            if (existing != null) {
                if (!existing.rename(backupName)) throw IOException("无法备份 USB 中的同名歌曲。")
                backup = existing
            }
            if (!temporary.rename(outputName)) throw IOException("USB 写入完成，但重命名失败。")
            if (temporary.length != expectedLength) throw IOException("USB 最终文件校验失败。")
            committed = true
            if (backup == null || backup.delete()) clear(record.token)
            record = null
            onProgress(100)
        } catch (e: Exception) {
            // Never delete a verified old song. If unplugged, retain the journal for next insertion.
            if (record != null && !committed) {
                val restored = runCatching { rollback(record) }.getOrDefault(false)
                if (restored) clear(record.token)
            } else if (!committed) runCatching { temporary.delete() }
            throw e
        }
    }

    private fun recover() {
        val transactions = readTransactions().filter { it.volumeId.equals(target.id, true) }
        for (record in transactions) {
            checkCancelled()
            if (record.preparing) {
                if (!rollback(record)) throw IOException("上次临时文件清理失败，请重新连接 USB 后重试。")
                clear(record.token)
                continue
            }
            val final = directory.find(record.outputName)
            val committed = if (final == null) false else try {
                verify(final, record.length, record.hash); true
            } catch (e: java.io.InterruptedIOException) { throw e }
            catch (e: IOException) { false }
            if (committed) {
                val backupGone = directory.find(record.backupName)?.delete() ?: true
                val temporaryGone = directory.find(record.temporaryName)?.delete() ?: true
                if (backupGone && temporaryGone) clear(record.token)
            } else if (rollback(record)) clear(record.token)
            else throw IOException("上次 USB 写入未完成，原歌曲备份保留为 ${record.backupName}。请重新连接后重试。")
        }
    }

    private fun rollback(record: Transaction): Boolean {
        storage.requireTarget(target)
        return rollbackUsbFiles(directory, record.outputName, record.originalName,
            record.temporaryName, record.backupName, record.preparing)
    }

    private fun copy(input: InputStream, output: OutputStream, total: Long, digest: MessageDigest) {
        val buffer = ByteArray(65_536)
        var count = 0L
        while (true) {
            checkCancelled()
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            output.write(buffer, 0, n)
            digest.update(buffer, 0, n)
            count += n
            onProgress(if (total <= 0) 0 else ((count * 70) / total).coerceIn(0, 70).toInt())
        }
        if (count != total) throw IOException("临时音频在复制过程中发生变化。")
    }

    private fun verify(file: DestinationFile, length: Long, hash: String) {
        if (file.length != length) throw IOException("USB 文件长度不一致，请检查空间和连接。")
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        file.input().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                digest.update(buffer, 0, n)
                read += n
                onProgress(if (length <= 0) 70 else 70 + ((read * 29) / length).coerceIn(0, 29).toInt())
            }
        }
        if (read != length || digest.digest().hex() != hash)
            throw IOException("USB 读回校验失败，已保留原歌曲。")
    }

    private fun readTransactions(): List<Transaction> = runCatching {
        val array = JSONArray(journal.getString("pending", "[]"))
        (0 until array.length()).map { Transaction.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(record: Transaction) {
        val records = readTransactions().filterNot { it.token == record.token } + record
        require(journal.edit().putString("pending", JSONArray(records.map { it.toJson() }).toString()).commit()) {
            "无法保存 USB 写入恢复记录。"
        }
    }

    private fun clear(token: String) {
        val records = readTransactions().filterNot { it.token == token }
        journal.edit().putString("pending", JSONArray(records.map { it.toJson() }).toString()).commit()
    }

    private data class Transaction(val token: String, val volumeId: String, val outputName: String,
        val originalName: String?, val temporaryName: String, val backupName: String,
        val length: Long, val hash: String, val preparing: Boolean) {
        fun toJson() = JSONObject().put("token", token).put("volume", volumeId).put("output", outputName)
            .put("original", originalName ?: JSONObject.NULL).put("temporary", temporaryName)
            .put("backup", backupName).put("length", length).put("hash", hash).put("preparing", preparing)
        companion object {
            fun fromJson(j: JSONObject) = Transaction(j.getString("token"), j.getString("volume"),
                j.getString("output"), if (j.isNull("original")) null else j.getString("original"),
                j.getString("temporary"), j.getString("backup"), j.getLong("length"), j.getString("hash"),
                j.optBoolean("preparing", false))
        }
    }
}

private class DocumentRoot(private val context: Context, private val root: DocumentFile) : DestinationRoot {
    override fun find(name: String): DestinationFile? {
        if (!root.exists() || !root.canRead()) throw IOException("USB 已断开或授权失效。")
        return root.listFiles().firstOrNull { it.name?.equals(name, true) == true }
            ?.let { DocumentDestination(context, it) }
    }
    override fun create(name: String): DestinationFile {
        val doc = root.createFile("application/octet-stream", name) ?: throw IOException("USB 临时文件创建失败。")
        if (doc.name != name) {
            doc.delete()
            throw IOException("USB 文件提供器无法保留文件名，请使用所有文件访问权限。")
        }
        return DocumentDestination(context, doc)
    }
}
private class DocumentDestination(private val context: Context, private val doc: DocumentFile) : DestinationFile {
    override val name get() = doc.name.orEmpty()
    override val length get() = doc.length()
    override val isFile get() = doc.isFile
    override fun input() = context.contentResolver.openInputStream(doc.uri) ?: throw IOException("USB 文件无法读取。")
    override fun output() = context.contentResolver.openOutputStream(doc.uri, "wt") ?: throw IOException("USB 文件无法写入。")
    override fun rename(name: String) = doc.renameTo(name) && doc.name == name
    override fun delete() = doc.delete()
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
