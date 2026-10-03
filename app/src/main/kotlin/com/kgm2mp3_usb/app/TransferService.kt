package com.kgm2mp3_usb.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.InterruptedIOException
import java.util.concurrent.Executors

class TransferService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var working = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification = 0L
    private val manager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "歌曲转换进度", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TransferController.cancel()
            if (!working) stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification(TransferController.snapshot)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, notification)
        if (working) return START_NOT_STICKY
        val batch = TransferController.current
        if (batch == null) {
            TransferController.restoreInterrupted(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        working = true
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kgx2mp3:transfer")
            .apply { setReferenceCounted(false); acquire(WAKE_TIMEOUT) }
        worker.execute { runBatch(batch) }
        return START_NOT_STICKY
    }

    private fun runBatch(batch: TransferController.Batch) {
        var successes = 0
        val failures = mutableListOf<TransferFailure>()
        var completed = 0
        var finalSnapshot = TransferSnapshot(stage = "任务中断", total = batch.songs.size)
        try {
            val engine = TransferEngine(this, batch.cancelled) progress@{ stage, progress ->
                if (TransferController.current !== batch || !TransferController.snapshot.running) return@progress
                TransferController.publish(TransferController.snapshot.copy(stage = stage, progress = progress))
                refreshNotification()
            }
            for ((index, song) in batch.songs.withIndex()) {
                if (batch.cancelled.get()) break
                if (wakeLock?.isHeld != true) wakeLock?.acquire(WAKE_TIMEOUT)
                TransferController.publish(TransferSnapshot(running = true, stage = "准备处理", position = index + 1,
                    total = batch.songs.size, successes = successes, failures = failures.toList(), currentName = song.name))
                refreshNotification(force = true)
                try {
                    engine.transfer(song, batch.target, batch.format)
                    successes++
                } catch (e: InterruptedIOException) {
                    batch.cancelled.set(true)
                    break
                } catch (e: Exception) {
                    failures += TransferFailure(song, userReason(e))
                }
                completed = index + 1
                TransferController.publish(TransferController.snapshot.copy(successes = successes, failures = failures.toList()))
                TransferController.checkpoint(this, completed)
            }
            if (batch.cancelled.get()) {
                failures += batch.songs.drop(completed).map { TransferFailure(it, "已取消，尚未确认完成，可重试。") }
            }
            finalSnapshot = TransferSnapshot(stage = if (batch.cancelled.get()) "已取消" else "处理完成",
                position = completed, total = batch.songs.size, successes = successes,
                failures = failures.toList(), progress = if (batch.cancelled.get()) 0 else 100)
        } catch (e: Exception) {
            failures += batch.songs.drop(completed).map { TransferFailure(it, userReason(e)) }
            finalSnapshot = TransferSnapshot(stage = "任务中断", position = completed,
                total = batch.songs.size, successes = successes, failures = failures.toList())
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
            working = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            TransferController.complete(this, finalSnapshot)
        }
    }

    private fun userReason(e: Exception): String = when (e) {
        is SecurityException -> "文件授权已失效，请重新授权。"
        is java.io.FileNotFoundException -> "文件不存在或 USB 已断开，请刷新后重试。"
        else -> e.message?.takeIf { it.any { c -> c.code in 0x3400..0x9FFF } }?.take(300)
            ?: "读取或写入失败，请检查歌曲、USB 连接和剩余空间。"
    }

    private fun refreshNotification(force: Boolean = false) {
        if (working && wakeLock?.isHeld != true) wakeLock?.acquire(WAKE_TIMEOUT)
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastNotification < 1_000) return
        lastNotification = now
        runCatching { manager.notify(NOTIFICATION, notification(TransferController.snapshot)) }
    }

    private fun notification(snapshot: TransferSnapshot): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("正在转换并转移歌曲")
            .setContentText("${snapshot.position}/${snapshot.total} · ${snapshot.stage} · ${snapshot.currentName}")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, snapshot.progress, snapshot.progress == 0)
            .addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        TransferController.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (working) TransferController.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.kgm2mp3_usb.app.START_TRANSFER"
        const val ACTION_CANCEL = "com.kgm2mp3_usb.app.CANCEL_TRANSFER"
        private const val CHANNEL = "transfer"
        private const val NOTIFICATION = 10
        private const val WAKE_TIMEOUT = 15 * 60 * 1_000L
    }
}
