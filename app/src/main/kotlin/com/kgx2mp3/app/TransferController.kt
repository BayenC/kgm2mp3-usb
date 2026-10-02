package com.kgx2mp3.app

import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Application-scoped state lets a recreated activity reattach without restarting a batch. */
// Batch recovery must be durable before a foreground task or USB replacement begins.
@SuppressLint("ApplySharedPref")
object TransferController {
    @Volatile var snapshot: TransferSnapshot = TransferSnapshot()
        private set
    private val listeners = CopyOnWriteArraySet<(TransferSnapshot) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile internal var current: Batch? = null
        private set
    private var lastPublication = 0L

    fun addListener(listener: (TransferSnapshot) -> Unit) {
        listeners.add(listener)
        main.post { if (listeners.contains(listener)) listener(snapshot) }
    }
    fun removeListener(listener: (TransferSnapshot) -> Unit) { listeners.remove(listener) }

    @Synchronized fun start(context: Context, songs: List<SongRef>, target: StorageTarget, format: OutputFormat) {
        require(!snapshot.running) { "正在转换歌曲，请等待完成。" }
        require(songs.isNotEmpty()) { "请先选择歌曲。" }
        val unique = songs.distinctBy { it.id }
        val valid = StorageRepository(context).requireTarget(target)
        current = Batch(unique, valid, format)
        persist(context, unique, 0, 0, emptyList())
        publish(TransferSnapshot(running = true, stage = "准备处理", total = unique.size))
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, TransferService::class.java).setAction(TransferService.ACTION_START))
        } catch (e: Exception) {
            context.getSharedPreferences("batch_journal", Context.MODE_PRIVATE).edit().clear().commit()
            current = null
            publish(snapshot.copy(running = false, stage = "启动失败",
                failures = unique.map { TransferFailure(it, "无法启动转换任务，请保持软件在前台并重试。") }))
            throw e
        }
    }

    fun cancel() { current?.cancelled?.set(true) }

    @Synchronized fun restoreInterrupted(context: Context) {
        if (current != null || snapshot.running) return
        val prefs = context.getSharedPreferences("batch_journal", Context.MODE_PRIVATE)
        val saved = prefs.getString("active", null) ?: return
        try {
            val json = JSONObject(saved)
            val songs = json.getJSONArray("songs").songs()
            val next = json.optInt("next", 0).coerceIn(0, songs.size)
            val failures = json.optJSONArray("failures")?.failures().orEmpty() +
                songs.drop(next).map { TransferFailure(it, "上次任务被系统中断，尚未确认完成，请重试。") }
            publish(TransferSnapshot(stage = "上次任务已中断", total = songs.size,
                position = next, successes = json.optInt("successes"), failures = failures))
        } catch (e: Exception) {
            publish(TransferSnapshot(stage = "上次任务已中断，请重新选择歌曲。"))
        }
        prefs.edit().clear().commit()
        // Old local working files belong only to interrupted jobs, never to the user's downloads.
        context.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("transfer-") }
            ?.forEach { it.deleteRecursively() }
    }

    internal fun publish(value: TransferSnapshot) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = snapshot
        snapshot = value
        val important = previous.running != value.running || previous.stage != value.stage ||
            previous.position != value.position || previous.successes != value.successes ||
            previous.failures.size != value.failures.size
        if (!important && now - lastPublication < 100) return
        lastPublication = now
        main.post { listeners.forEach { it(snapshot) } }
    }

    internal fun checkpoint(context: Context, next: Int) {
        val batch = current ?: return
        persist(context, batch.songs, next, snapshot.successes, snapshot.failures)
    }

    @Synchronized internal fun complete(context: Context, final: TransferSnapshot) {
        context.getSharedPreferences("batch_journal", Context.MODE_PRIVATE).edit().clear().commit()
        current = null
        publish(final.copy(running = false))
    }

    private fun persist(context: Context, songs: List<SongRef>, next: Int, successes: Int, failures: List<TransferFailure>) {
        val json = JSONObject().put("songs", JSONArray(songs.map(::songJson))).put("next", next)
            .put("successes", successes).put("failures", JSONArray(failures.map {
                JSONObject().put("song", songJson(it.song)).put("reason", it.reason)
            }))
        check(context.getSharedPreferences("batch_journal", Context.MODE_PRIVATE).edit()
            .putString("active", json.toString()).commit()) { "无法保存任务进度。" }
    }

    internal class Batch(val songs: List<SongRef>, val target: StorageTarget, val format: OutputFormat) {
        val cancelled = AtomicBoolean(false)
    }

    private fun songJson(song: SongRef) = JSONObject().put("id", song.id).put("name", song.name)
        .put("uri", song.sourceUri ?: JSONObject.NULL).put("path", song.path ?: JSONObject.NULL)
        .put("size", song.size).put("modified", song.lastModified).put("encrypted", song.encrypted)
    private fun songFromJson(j: JSONObject) = SongRef(j.getString("id"), j.getString("name"),
        if (j.isNull("uri")) null else j.getString("uri"), if (j.isNull("path")) null else j.getString("path"),
        j.getLong("size"), j.getLong("modified"), j.getBoolean("encrypted"))
    private fun JSONArray.songs() = (0 until length()).map { songFromJson(getJSONObject(it)) }
    private fun JSONArray.failures() = (0 until length()).map {
        val item = getJSONObject(it)
        TransferFailure(songFromJson(item.getJSONObject("song")), item.getString("reason"))
    }
}
