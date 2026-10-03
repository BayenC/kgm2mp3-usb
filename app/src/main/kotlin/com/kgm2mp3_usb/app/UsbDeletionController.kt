package com.kgm2mp3_usb.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class UsbDeletionFailure(val song: SongRef, val reason: String)
data class UsbDeletionSnapshot(
    val running: Boolean = false,
    val targetId: String = "",
    val stage: String = "",
    val position: Int = 0,
    val total: Int = 0,
    val deleted: Int = 0,
    val currentName: String = "",
    val deletedIds: Set<String> = emptySet(),
    val failures: List<UsbDeletionFailure> = emptyList(),
    val cancelled: Boolean = false,
    val interrupted: Boolean = false,
)

/** Deletion survives activity recreation; a killed process records interruption, never a resume. */
@SuppressLint("ApplySharedPref") // The interruption marker must be durable before deleting anything.
object UsbDeletionController {
    @Volatile var snapshot = UsbDeletionSnapshot()
        private set
    private val listeners = CopyOnWriteArraySet<(UsbDeletionSnapshot) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var current: Batch? = null

    fun addListener(listener: (UsbDeletionSnapshot) -> Unit) {
        listeners.add(listener)
        main.post { if (listeners.contains(listener)) listener(snapshot) }
    }
    fun removeListener(listener: (UsbDeletionSnapshot) -> Unit) { listeners.remove(listener) }

    @Synchronized fun start(context: Context, songs: List<SongRef>, target: StorageTarget) {
        require(songs.isNotEmpty()) { "请逐首选择要删除的 USB 歌曲。" }
        require(!snapshot.running) { "正在删除歌曲，请等待完成。" }
        val app = context.applicationContext
        val storage = StorageRepository(app)
        val valid = storage.requireTarget(target)
        val batch = Batch(songs.distinctBy { it.id }.toList(), valid.copy())
        check(UsbOperationGate.acquire(batch)) { "正在处理歌曲，请等待当前任务完成。" }
        try {
            current = batch
            val initial = UsbDeletionSnapshot(running = true, targetId = valid.id,
                stage = "准备删除", total = batch.songs.size)
            checkpoint(app, initial)
            publish(initial)
            worker.execute { runBatch(app, storage, batch) }
        } catch (e: Exception) {
            current = null
            UsbOperationGate.release(batch)
            clearMarker(app)
            publish(UsbDeletionSnapshot(stage = "未能开始删除"))
            throw e
        }
    }

    fun cancel() { current?.cancelled?.set(true) }

    @Synchronized fun restoreInterrupted(context: Context) {
        if (current != null || snapshot.running) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return
        publish(UsbDeletionSnapshot(targetId = prefs.getString("target", "").orEmpty(),
            stage = "上次删除已中断，请刷新后重新选择", total = prefs.getInt("total", 0),
            deleted = prefs.getInt("deleted", 0), interrupted = true))
        prefs.edit().clear().commit()
    }

    private fun runBatch(context: Context, storage: StorageRepository, batch: Batch) {
        val deletedIds = linkedSetOf<String>()
        val failures = mutableListOf<UsbDeletionFailure>()
        var position = 0
        var stopReason: String? = null
        try {
            for ((index, song) in batch.songs.withIndex()) {
                if (batch.cancelled.get()) { stopReason = "已取消，尚未删除。"; break }
                position = index + 1
                publish(UsbDeletionSnapshot(running = true, targetId = batch.target.id, stage = "正在删除",
                    position = position, total = batch.songs.size, deleted = deletedIds.size,
                    currentName = song.name, deletedIds = deletedIds.toSet(), failures = failures.toList()))
                try {
                    storage.deleteUsbSong(batch.target, song) {
                        if (batch.cancelled.get()) throw InterruptedIOException("已取消，尚未删除。")
                    }
                    deletedIds.add(song.id)
                } catch (e: InterruptedIOException) { stopReason = "已取消，尚未删除。"; position = index; break }
                catch (e: UsbTargetUnavailableException) { stopReason = e.message; position = index; break }
                catch (e: UsbAccessRevokedException) { stopReason = e.message; position = index; break }
                catch (e: SecurityException) { stopReason = "USB 授权已失效，请重新授权后重新选择。"; position = index; break }
                catch (e: Exception) {
                    failures.add(UsbDeletionFailure(song, userReason(e)))
                }
                checkpoint(context, UsbDeletionSnapshot(targetId = batch.target.id, total = batch.songs.size,
                    deleted = deletedIds.size))
            }
        } catch (e: Exception) { stopReason = userReason(e) }
        finally {
            if (stopReason != null) failures.addAll(batch.songs.drop(position).map {
                UsbDeletionFailure(it, stopReason ?: "尚未删除，请重新选择。")
            })
            val final = UsbDeletionSnapshot(targetId = batch.target.id,
                stage = when {
                    batch.cancelled.get() -> "删除已取消"
                    stopReason != null -> "删除已停止"
                    else -> "删除完成"
                }, position = position, total = batch.songs.size, deleted = deletedIds.size,
                deletedIds = deletedIds.toSet(), failures = failures.toList(), cancelled = batch.cancelled.get())
            clearMarker(context)
            current = null
            UsbOperationGate.release(batch)
            publish(final)
        }
    }

    private fun userReason(e: Exception): String = e.message
        ?.takeIf { it.any { c -> c.code in 0x3400..0x9FFF } }?.take(300)
        ?: "歌曲无法删除，请刷新后检查连接和文件授权。"

    private fun checkpoint(context: Context, state: UsbDeletionSnapshot) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", true).putString("target", state.targetId)
            .putInt("total", state.total).putInt("deleted", state.deleted).commit()) { "无法保存删除任务状态。" }
    }
    private fun clearMarker(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit() }
    private fun publish(value: UsbDeletionSnapshot) {
        snapshot = value
        main.post { listeners.forEach { if (listeners.contains(it)) it(value) } }
    }
    private class Batch(val songs: List<SongRef>, val target: StorageTarget) { val cancelled = AtomicBoolean(false) }
    private const val PREFS = "usb_deletion_state"
}
