package com.kgm2mp3_usb.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var settings: SettingsStore
    private lateinit var storage: StorageRepository
    private lateinit var updates: UpdateManager
    private val main = Handler(Looper.getMainLooper())
    private val scanner = Executors.newSingleThreadExecutor()
    private var scanGeneration = 0
    private var scanning = false
    private var resumed = false
    private var phoneTab = true
    private val phoneSelection = linkedSetOf<String>()
    private val usbSelection = linkedMapOf<String, SongRef>()
    private var usbSelectionTargetId: String? = null
    private var phone = ScanResult(emptyList())
    private var usb = ScanResult(emptyList())
    private var target: StorageTarget? = null
    private var snapshot = TransferSnapshot()
    private var deletion = UsbDeletionSnapshot()
    private var lastOperation = "transfer"
    private var shownCompletion = ""
    private var shownDeletionCompletion = ""
    private var observer: FileObserver? = null
    private var pendingInstall = false
    private var scanAfterTransfer = false
    private var cancelling = false
    private val openDialogs = mutableSetOf<AlertDialog>()
    private var compactLayout = false

    private lateinit var root: LinearLayout
    private val formatButtons = linkedMapOf<OutputFormat, Button>()
    private lateinit var permission: Button
    private lateinit var phoneButton: Button
    private lateinit var usbButton: Button
    private lateinit var count: TextView
    private lateinit var selectAll: Button
    private lateinit var refresh: Button
    private lateinit var delete: Button
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var emptyAction: Button
    private lateinit var transfer: Button
    private lateinit var progressCard: LinearLayout
    private lateinit var progressTitle: TextView
    private lateinit var progressMessage: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressActions: LinearLayout
    private lateinit var cancel: Button
    private lateinit var retry: Button
    private lateinit var failureDetails: Button
    private val adapter = SongsAdapter()

    private val transferListener: (TransferSnapshot) -> Unit = { value ->
        main.post { if (!isDestroyed) renderTransfer(value) }
    }
    private val deletionListener: (UsbDeletionSnapshot) -> Unit = { value ->
        main.post { if (!isDestroyed) renderDeletion(value) }
    }
    private val busy: Boolean
        get() = TransferController.busy || snapshot.running || deletion.running
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action in setOf(Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_EJECT)) {
                val removed = intent?.data
                val activeDeletion = UsbDeletionController.snapshot
                if (activeDeletion.running && (removed == null || removed.path == target?.rootPath ||
                    removed.lastPathSegment.equals(activeDeletion.targetId, true))) {
                    UsbDeletionController.cancel()
                    cancelling = true
                    renderDeletion(activeDeletion)
                }
                if (target == null || removed?.path == target?.rootPath || removed?.lastPathSegment.equals(target?.id, true)) {
                    clearUsbSelection()
                    renderSongs()
                }
            }
            scheduleScan(400)
        }
    }
    private val periodic = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (!busy) refreshSongs()
            main.postDelayed(this, 7_000)
        }
    }
    private val scheduledScan = Runnable { if (resumed && !busy) refreshSongs() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        settings = SettingsStore(this)
        storage = StorageRepository(this)
        updates = UpdateManager(applicationContext)
        TransferController.restoreInterrupted(this)
        UsbDeletionController.restoreInterrupted(this)
        phoneTab = state?.getBoolean("phoneTab", true) ?: true
        if (state?.getBoolean("restoreSelection", false) == true) {
            restoreSelection()
        }
        pendingInstall = state?.getBoolean("pendingInstall", false) ?: false
        shownCompletion = state?.getString("shownCompletion") ?: ""
        shownDeletionCompletion = state?.getString("shownDeletionCompletion") ?: ""
        lastOperation = state?.getString("lastOperation") ?: "transfer"
        buildScreen()
        TransferController.addListener(transferListener)
        UsbDeletionController.addListener(deletionListener)
        renderTransfer(TransferController.snapshot)
        renderDeletion(UsbDeletionController.snapshot)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshSongs()
        observePhone()
        main.removeCallbacks(periodic)
        main.postDelayed(periodic, 7_000)
        if ((pendingInstall || updates.waitingForInstallPermission()) && updates.canInstall()) {
            pendingInstall = false
            updates.readyUpdate()?.let(::installUpdate)
        }
    }

    override fun onStop() {
        resumed = false
        observer?.stopWatching()
        observer = null
        main.removeCallbacks(periodic)
        main.removeCallbacks(scheduledScan)
        super.onStop()
    }

    override fun onDestroy() {
        openDialogs.toList().forEach { it.dismiss() }
        openDialogs.clear()
        TransferController.removeListener(transferListener)
        UsbDeletionController.removeListener(deletionListener)
        unregisterReceiver(mediaReceiver)
        scanner.shutdownNow()
        updates.close()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("phoneTab", phoneTab)
        // Song IDs may contain long Chinese paths. Keep large selections out of Binder's state bundle.
        persistSelection()
        out.putBoolean("restoreSelection", true)
        out.putBoolean("pendingInstall", pendingInstall)
        out.putString("shownCompletion", shownCompletion)
        out.putString("shownDeletionCompletion", shownDeletionCompletion)
        out.putString("lastOperation", lastOperation)
        super.onSaveInstanceState(out)
    }

    private fun useCompactLayout(): Boolean {
        val density = resources.displayMetrics.density
        val availableHeight = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            (metrics.bounds.height() - bars.top - bars.bottom) / density
        } else resources.configuration.screenHeightDp.toFloat()
        return availableHeight / resources.configuration.fontScale.coerceAtLeast(1f) < 590f
    }

    private fun buildScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        compactLayout = useCompactLayout()
        val sidePadding = if (compactLayout) 12 else 20
        val verticalPadding = if (compactLayout) 6 else 12
        val controlHeight = if (compactLayout) 48 else 52
        root = column().apply { setBackgroundColor(BACKGROUND); setPadding(dp(sidePadding), dp(verticalPadding), dp(sidePadding), dp(verticalPadding)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(sidePadding) + bars.left, dp(verticalPadding) + bars.top, dp(sidePadding) + bars.right, dp(verticalPadding) + bars.bottom)
            insets
        }
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = column()
        heading.addView(label("音乐转移", if (compactLayout) 24 else 28, INK, true))
        if (!compactLayout) heading.addView(label("选好歌曲，一键放进 U 盘", 16, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        header.addView(heading, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(action("设置", false) { showSettings() }, LinearLayout.LayoutParams(dp(if (compactLayout) 72 else 78), dp(if (compactLayout) 48 else 54)))
        root.addView(header, margins(MATCH, WRAP, bottom = if (compactLayout) 8 else 20))

        val formatCard = column().apply {
            background = rounded(WHITE, 18)
            setPadding(dp(if (compactLayout) 10 else 14), dp(if (compactLayout) 6 else 12), dp(if (compactLayout) 10 else 14), dp(if (compactLayout) 6 else 14))
        }
        formatCard.addView(label("输出格式", if (compactLayout) 18 else 19, INK, true), margins(MATCH, WRAP, bottom = if (compactLayout) 4 else 9))
        val formatRow = row()
        OutputFormat.entries.forEachIndexed { index, format ->
            val button = action(format.displayName, settings.outputFormat == format) {
                if (!busy) {
                    settings.outputFormat = format
                    renderSongs()
                }
            }.apply {
                textSize = 17f
                setSingleLine(true)
                setPadding(dp(3), 0, dp(3), 0)
                contentDescription = "输出格式 ${format.displayName}"
            }
            formatButtons[format] = button
            formatRow.addView(button, LinearLayout.LayoutParams(0, dp(if (compactLayout) 48 else 54), 1f).apply {
                if (index != OutputFormat.entries.lastIndex) marginEnd = dp(if (compactLayout) 4 else 5)
            })
        }
        formatCard.addView(formatRow)
        root.addView(formatCard, margins(MATCH, WRAP, bottom = if (compactLayout) 6 else 12))

        permission = action("首次使用：授权读取音乐和 USB", false) { requestFileAccess() }.apply {
            textSize = 17f
            visibility = View.GONE
        }
        root.addView(permission, margins(MATCH, dp(if (compactLayout) 48 else 58), bottom = if (compactLayout) 6 else 12))

        val tabs = row().apply {
            background = rounded(SOFT, 14)
            val padding = dp(if (compactLayout) 2 else 4)
            setPadding(padding, padding, padding, padding)
        }
        phoneButton = action("手机歌曲", false) { switchTab(true) }
        usbButton = action("USB 歌曲", false) { switchTab(false) }
        tabs.addView(phoneButton, LinearLayout.LayoutParams(0, dp(controlHeight), 1f))
        tabs.addView(usbButton, LinearLayout.LayoutParams(0, dp(controlHeight), 1f))
        root.addView(tabs, margins(MATCH, WRAP, bottom = if (compactLayout) 4 else 8))

        val toolbar = if (compactLayout) row().apply { gravity = Gravity.CENTER_VERTICAL } else column()
        count = label("正在读取歌曲…", if (compactLayout) 16 else 17, MUTED).apply {
            if (compactLayout) { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        }
        toolbar.addView(count, if (compactLayout) LinearLayout.LayoutParams(0, WRAP, 1f) else margins(MATCH, WRAP, top = 8, bottom = 3))
        val toolbarActions = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        refresh = action("刷新", false) { refreshSongs() }.apply { textSize = 17f; minimumWidth = dp(if (compactLayout) 64 else 80) }
        selectAll = action("全选", false) {
            if (busy || !phoneTab) return@action
            if (phone.songs.isNotEmpty() && phone.songs.all { phoneSelection.contains(it.id) }) phoneSelection.clear()
            else phoneSelection.addAll(phone.songs.map { it.id })
            renderSongs()
        }.apply { textSize = 17f; minimumWidth = dp(if (compactLayout) 72 else 86) }
        delete = action("删除", false) { confirmDelete() }.apply {
            textSize = 17f
            minimumWidth = dp(if (compactLayout) 64 else 80)
            contentDescription = "删除已逐首勾选的 USB 歌曲"
            visibility = View.GONE
        }
        toolbarActions.addView(selectAll, LinearLayout.LayoutParams(WRAP, dp(controlHeight)).apply {
            marginEnd = dp(if (compactLayout) 4 else 6)
        })
        toolbarActions.addView(refresh, LinearLayout.LayoutParams(WRAP, dp(controlHeight)))
        toolbarActions.addView(delete, LinearLayout.LayoutParams(WRAP, dp(controlHeight)).apply { marginStart = dp(if (compactLayout) 4 else 6) })
        toolbar.addView(toolbarActions)
        root.addView(toolbar, margins(MATCH, WRAP, bottom = if (compactLayout) 4 else 6))

        val listContainer = android.widget.FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                if (!busy) {
                    val song = (if (phoneTab) phone.songs else usb.songs)[position]
                    if (phoneTab) {
                        if (!phoneSelection.add(song.id)) phoneSelection.remove(song.id)
                    } else {
                        val chosen = target ?: return@setOnItemClickListener
                        if (usb.error != null) return@setOnItemClickListener
                        if (usbSelectionTargetId != chosen.id) clearUsbSelection()
                        usbSelectionTargetId = chosen.id
                        if (usbSelection.containsKey(song.id)) usbSelection.remove(song.id)
                        else usbSelection[song.id] = song
                    }
                    renderSongs()
                }
            }
        }
        listContainer.addView(list, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        empty = column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(if (compactLayout) 8 else 20), dp(if (compactLayout) 6 else 24), dp(if (compactLayout) 8 else 20), dp(if (compactLayout) 6 else 24))
        }
        emptyTitle = label("正在读取歌曲…", if (compactLayout) 20 else 22, INK, true).apply {
            gravity = Gravity.CENTER
            if (compactLayout) { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        }
        emptyMessage = label("稍等片刻", 17, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(if (compactLayout) 4 else 12), 0, dp(if (compactLayout) 4 else 18))
            if (compactLayout) { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        }
        emptyAction = action("选择歌曲文件夹", false) { if (phoneTab) choosePhone() else chooseUsb() }
        empty.addView(emptyTitle)
        empty.addView(emptyMessage, if (compactLayout) LinearLayout.LayoutParams(MATCH, 0, 1f) else LinearLayout.LayoutParams(MATCH, WRAP))
        empty.addView(emptyAction, LinearLayout.LayoutParams(MATCH, dp(if (compactLayout) 48 else 56)))
        listContainer.addView(empty, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(listContainer, LinearLayout.LayoutParams(MATCH, 0, 1f))

        progressCard = column().apply {
            background = rounded(SOFT, 16)
            setPadding(dp(if (compactLayout) 8 else 16), dp(if (compactLayout) 6 else 14), dp(if (compactLayout) 8 else 16), dp(if (compactLayout) 6 else 12))
            visibility = View.GONE
        }
        progressTitle = label("正在处理", if (compactLayout) 18 else 20, INK, true).apply {
            if (compactLayout) { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        }
        progressMessage = label("", if (compactLayout) 15 else 16, MUTED).apply { maxLines = if (compactLayout) 1 else 2; ellipsize = TextUtils.TruncateAt.END }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(TEAL)
        }
        progressActions = row()
        cancel = action("取消", false) {
            if (deletion.running) UsbDeletionController.cancel() else TransferController.cancel()
            cancelling = true
            cancel.isEnabled = false
            cancel.text = if (compactLayout) "取消中" else "正在取消…"
        }.apply { if (compactLayout) textSize = 17f }
        retry = action(if (compactLayout) "重试" else "重试失败歌曲", false) { retryFailures() }.apply {
            visibility = View.GONE
            contentDescription = "重试失败歌曲"
        }
        failureDetails = action(if (compactLayout) "原因" else "查看未删除原因", false) { showDeletionFailures(deletion) }.apply {
            visibility = View.GONE
            contentDescription = "查看未删除原因"
        }
        progressActions.addView(retry, LinearLayout.LayoutParams(0, dp(controlHeight), 1f))
        progressActions.addView(failureDetails, LinearLayout.LayoutParams(0, dp(controlHeight), 1f))
        progressActions.addView(cancel, LinearLayout.LayoutParams(0, dp(controlHeight), 1f))
        if (compactLayout) {
            val progressSummary = column().apply {
                addView(progressTitle)
                addView(progressMessage, margins(MATCH, WRAP, top = 2))
            }
            val progressRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
            progressRow.addView(progressSummary, LinearLayout.LayoutParams(0, WRAP, 1f))
            progressRow.addView(progressActions, LinearLayout.LayoutParams(dp(88), WRAP).apply { marginStart = dp(6) })
            progressCard.addView(progressRow)
            progressCard.addView(progressBar, margins(MATCH, dp(4), top = 4))
        } else {
            progressCard.addView(progressTitle)
            progressCard.addView(progressMessage, margins(MATCH, WRAP, top = 5))
            progressCard.addView(progressBar, margins(MATCH, dp(8), top = 12, bottom = 4))
            progressCard.addView(progressActions)
        }
        root.addView(progressCard, margins(MATCH, WRAP, top = if (compactLayout) 4 else 8, bottom = if (compactLayout) 4 else 8))

        transfer = action("转移", true) { beginTransfer() }
        root.addView(transfer, margins(MATCH, dp(if (compactLayout) 56 else 64), top = if (compactLayout) 6 else 10))
        renderSongs()
    }

    private fun switchTab(value: Boolean) { phoneTab = value; renderSongs() }

    private fun refreshSongs() {
        if (isDestroyed || scanning || busy) return
        scanning = true
        val generation = ++scanGeneration
        refresh.isEnabled = false
        scanner.execute {
            val result = runCatching {
                val phoneScan = storage.scanPhone()
                val chosen = storage.selectedUsb()
                val usbScan = chosen?.let(storage::scanUsb) ?: ScanResult(emptyList())
                Triple(phoneScan, chosen, usbScan)
            }
            main.post {
                if (isDestroyed || generation != scanGeneration) return@post
                scanning = false
                if (busy) { renderSongs(); return@post }
                result.onSuccess { (newPhone, newTarget, newUsb) ->
                    phone = newPhone
                    target = newTarget
                    usb = newUsb
                    // Preserve choices across refreshes while a download is being completed.
                    phoneSelection.retainAll(phone.songs.map { it.id }.toSet())
                    reconcileUsbSelection(target, usb)
                }.onFailure {
                    phone = ScanResult(emptyList(), it.message ?: "暂时无法读取歌曲。")
                    clearUsbSelection()
                }
                renderSongs()
            }
        }
    }

    private fun renderSongs() {
        if (!::list.isInitialized) return
        val songs = if (phoneTab) phone.songs else usb.songs
        val error = if (phoneTab) phone.error else usb.error
        val phoneSelected = phone.songs.count { phoneSelection.contains(it.id) }
        val selected = if (phoneTab) phoneSelected else usbSelection.size
        phoneButton.background = buttonBackground(if (phoneTab) WHITE else Color.TRANSPARENT, SOFT_PRESSED, 11)
        usbButton.background = buttonBackground(if (!phoneTab) WHITE else Color.TRANSPARENT, SOFT_PRESSED, 11)
        phoneButton.setTextColor(buttonTextColors(if (phoneTab) TEAL else MUTED))
        usbButton.setTextColor(buttonTextColors(if (!phoneTab) TEAL else MUTED))
        phoneButton.isSelected = phoneTab
        usbButton.isSelected = !phoneTab
        count.text = if (compactLayout) "${songs.size} 首\n已选 $selected 首" else "${songs.size} 首 · 已选 $selected 首"
        selectAll.visibility = if (phoneTab) View.VISIBLE else View.GONE
        selectAll.isEnabled = phoneTab && songs.isNotEmpty() && !busy
        selectAll.text = if (phone.songs.isNotEmpty() && phoneSelected == phone.songs.size) "取消全选" else "全选"
        refresh.isEnabled = !busy && !scanning
        delete.visibility = if (phoneTab) View.GONE else View.VISIBLE
        delete.isEnabled = !phoneTab && target != null && usb.error == null && usbSelection.isNotEmpty() && !busy
        delete.background = buttonBackground(ROSE, ROSE_PRESSED, 13)
        delete.setTextColor(buttonTextColors(DANGER))
        permission.visibility = if (!hasFileAccess() && settings.sourceTreeUri == null && !busy) View.VISIBLE else View.GONE
        val chosen = target
        formatButtons.forEach { (format, button) ->
            val active = settings.outputFormat == format
            button.background = buttonBackground(if (active) TEAL else SOFT, if (active) TEAL_PRESSED else SOFT_PRESSED, 12)
            button.setTextColor(buttonTextColors(if (active) WHITE else TEAL))
            button.isSelected = active
            button.isEnabled = !busy
            button.contentDescription = "输出格式 ${format.displayName}${if (active) "，已选择" else ""}"
        }
        empty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
        when {
            !phoneTab && chosen == null -> {
                emptyTitle.text = "还没有连接 U 盘"
                emptyMessage.text = "插入 USB 后，选择要存放歌曲的 U 盘。"
                emptyAction.text = "选择 USB"
            }
            error != null -> {
                emptyTitle.text = "暂时读不到歌曲"
                emptyMessage.text = error
                emptyAction.text = if (phoneTab) "选择歌曲文件夹" else "重新选择 USB"
            }
            phoneTab -> {
                emptyTitle.text = "这里还没有歌曲"
                emptyMessage.text = "先在酷狗概念版下载歌曲，下载完成后会自动显示。"
                emptyAction.text = "选择歌曲文件夹"
            }
            else -> {
                emptyTitle.text = "U 盘里还没有歌曲"
                emptyMessage.text = "在「手机歌曲」里选好歌，再点「转移」。"
                emptyAction.text = "查看手机歌曲"
            }
        }
        emptyAction.setOnClickListener {
            if (!phoneTab && chosen != null && error == null) switchTab(true)
            else if (phoneTab) choosePhone() else chooseUsb()
        }
        emptyAction.isEnabled = !busy
        transfer.visibility = if (phoneTab) View.VISIBLE else View.GONE
        transfer.isEnabled = phoneTab && phoneSelected > 0 && !busy
        transfer.text = if (snapshot.running) "正在转移…" else "转移"
        adapter.notifyDataSetChanged()
    }

    private fun beginTransfer() {
        val songs = phone.songs.filter { phoneSelection.contains(it.id) }
        if (!phoneTab || songs.isEmpty() || busy) return
        val chosen = target ?: run {
            if (runCatching { storage.usbTargets() }.getOrDefault(emptyList()).isEmpty()) {
                switchTab(false)
                message("请插入 USB", "插入 U 盘后，在「USB 歌曲」里选择 USB 根目录，再回到「手机歌曲」点「转移」。")
            } else chooseUsb()
            return
        }
        ensureNotificationPermission()
        shownCompletion = ""
        cancelling = false
        lastOperation = "transfer"
        runCatching { TransferController.start(this, songs, chosen, settings.outputFormat) }
            .onSuccess { invalidateScan(); renderTransfer(TransferController.snapshot) }
            .onFailure { message("未能开始", it.message ?: "请重新选择 USB 后再试。") }
    }

    private fun renderTransfer(value: TransferSnapshot) {
        val wasRunning = snapshot.running
        snapshot = value
        if (value.running) {
            lastOperation = "transfer"
            scanAfterTransfer = true
        } else if (value.total > 0) {
            if (wasRunning || scanAfterTransfer) {
                cancelling = false
                scanAfterTransfer = false
                if (resumed) refreshSongs()
                val completionKey = "${value.total}:${value.position}:${value.successes}:${value.failures.hashCode()}"
                if (value.failures.isNotEmpty() && shownCompletion != completionKey) {
                    shownCompletion = completionKey
                    showFailures(value)
                }
            }
        }
        renderProgress()
        renderSongs()
    }

    private fun retryFailures() {
        if (busy) return
        val chosen = target ?: run { chooseUsb(); return }
        val songs = snapshot.failures.map { it.song }
        if (songs.isEmpty()) return
        shownCompletion = ""
        cancelling = false
        lastOperation = "transfer"
        runCatching { TransferController.start(this, songs, chosen, settings.outputFormat) }
            .onSuccess { invalidateScan(); renderTransfer(TransferController.snapshot) }
            .onFailure { message("未能重试", it.message ?: "请检查 U 盘。") }
    }

    private fun confirmDelete() {
        if (phoneTab || busy || usbSelection.isEmpty()) return
        val chosen = target ?: return
        if (usb.error != null || usbSelectionTargetId != chosen.id) {
            clearUsbSelection()
            renderSongs()
            message("请重新选择歌曲", "USB 或授权已变更，请刷新列表后逐首勾选。")
            return
        }
        // Freeze the device and the exact metadata at confirmation time. The controller rechecks each file.
        val songs = usbSelection.values.toList()
        val dialog = AlertDialog.Builder(this).setTitle("删除 USB 歌曲")
            .setMessage("删除 USB 中选中的 ${songs.size} 首歌曲？\n\n手机歌曲会保留，删除后无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (busy) return@setPositiveButton
                cancelling = false
                lastOperation = "delete"
                shownDeletionCompletion = ""
                runCatching { UsbDeletionController.start(this, songs, chosen) }
                    .onSuccess { invalidateScan(); renderDeletion(UsbDeletionController.snapshot) }
                    .onFailure {
                        clearUsbSelection()
                        refreshSongs()
                        renderSongs()
                        message("未能开始删除", it.message ?: "请检查 USB，刷新后重新逐首选择歌曲。")
                    }
            }.create()
        showLarge(dialog)
    }

    private fun renderDeletion(value: UsbDeletionSnapshot) {
        val wasRunning = deletion.running
        deletion = value
        if (value.running || (value.interrupted && snapshot.total == 0)) lastOperation = "delete"
        if (usbSelectionTargetId == value.targetId) value.deletedIds.forEach { usbSelection.remove(it) }
        if (value.interrupted) clearUsbSelection()
        if (wasRunning && !value.running) {
            cancelling = false
            if (resumed) refreshSongs()
            val key = "${value.targetId}:${value.total}:${value.deleted}:${value.failures.hashCode()}"
            if (!value.cancelled && !value.interrupted && value.failures.isNotEmpty() && shownDeletionCompletion != key) {
                shownDeletionCompletion = key
                showDeletionFailures(value)
            }
        }
        renderProgress()
        renderSongs()
    }

    private fun renderProgress() {
        val showDeletion = deletion.running || (!snapshot.running && lastOperation == "delete" &&
            (deletion.total > 0 || deletion.interrupted))
        if (showDeletion) {
            val value = deletion
            progressCard.visibility = View.VISIBLE
            retry.visibility = View.GONE
            if (value.running) {
                progressTitle.text = "正在删除 ${value.position.coerceAtLeast(1)} / ${value.total} 首"
                progressMessage.text = taskMessage(value.stage, value.currentName)
                progressBar.visibility = View.VISIBLE
                progressBar.progress = ((value.deleted + value.failures.size) * 100 / value.total.coerceAtLeast(1)).coerceIn(0, 100)
                cancel.visibility = View.VISIBLE
                cancel.isEnabled = !cancelling
                cancel.text = if (cancelling) { if (compactLayout) "取消中" else "正在取消…" } else "取消"
                failureDetails.visibility = View.GONE
            } else {
                progressTitle.text = if (value.interrupted) "上次删除已中断" else
                    "已删除 ${value.deleted} 首，未删除 ${(value.total - value.deleted).coerceAtLeast(0)} 首"
                progressMessage.text = when {
                    value.interrupted -> "请刷新 USB，再逐首选择需要删除的歌曲。"
                    value.cancelled -> "删除已停止，尚未删除的歌曲仍在 U 盘。"
                    value.failures.isNotEmpty() -> value.failures.first().let { "${it.song.name}：${it.reason}" }
                    else -> "USB 歌曲已删除，手机歌曲保留。"
                }
                progressBar.visibility = View.GONE
                cancel.visibility = View.GONE
                failureDetails.visibility = if (value.failures.isNotEmpty()) View.VISIBLE else View.GONE
                failureDetails.isEnabled = !busy
            }
            renderProgressActions()
            return
        }
        val value = snapshot
        progressCard.visibility = if (value.running || value.total > 0) View.VISIBLE else View.GONE
        failureDetails.visibility = View.GONE
        if (value.running) {
            progressTitle.text = "正在处理 ${value.position.coerceAtLeast(1)} / ${value.total} 首"
            progressMessage.text = taskMessage(value.stage, value.currentName)
            progressBar.visibility = View.VISIBLE
            progressBar.progress = value.progress.coerceIn(0, 100)
            cancel.visibility = View.VISIBLE
            cancel.isEnabled = !cancelling
            cancel.text = if (cancelling) { if (compactLayout) "取消中" else "正在取消…" } else "取消"
            retry.visibility = View.GONE
        } else {
            val unfinished = (value.total - value.successes - value.failures.size).coerceAtLeast(0)
            progressTitle.text = "成功 ${value.successes} 首${if (value.failures.isNotEmpty()) "，失败 ${value.failures.size} 首" else ""}"
            progressMessage.text = if (unfinished > 0) "已停止，剩余 $unfinished 首未处理。手机原文件仍保留。"
                else if (value.failures.isNotEmpty()) value.failures.first().let { "${it.song.name}：${it.reason}" }
                else "歌曲已放进 U 盘，可以拔出后在车上播放。"
            progressBar.visibility = View.GONE
            cancel.visibility = View.GONE
            retry.visibility = if (value.failures.isNotEmpty()) View.VISIBLE else View.GONE
            retry.isEnabled = !busy
        }
        renderProgressActions()
    }

    private fun renderProgressActions() {
        progressActions.visibility = if (cancel.visibility == View.VISIBLE || retry.visibility == View.VISIBLE ||
            failureDetails.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun taskMessage(stage: String, name: String) = stage + if (name.isNotBlank()) " · $name" else ""

    private fun showDeletionFailures(value: UsbDeletionSnapshot) {
        if (!resumed || value.failures.isEmpty()) return
        val detail = value.failures.take(20).joinToString("\n\n") { "${it.song.name}\n${it.reason}" } +
            if (value.failures.size > 20) "\n\n另有 ${value.failures.size - 20} 首未删除。" else ""
        val dialog = AlertDialog.Builder(this).setTitle("${value.failures.size} 首歌曲未删除")
            .setMessage(detail).setPositiveButton("知道了", null).create()
        showLarge(dialog)
    }

    private fun clearUsbSelection() {
        usbSelection.clear()
        usbSelectionTargetId = null
    }

    private fun invalidateScan() {
        scanGeneration++
        scanning = false
    }

    private fun reconcileUsbSelection(chosen: StorageTarget?, result: ScanResult) {
        if (chosen == null || result.error != null ||
            (usbSelectionTargetId != null && usbSelectionTargetId != chosen.id)) {
            clearUsbSelection()
        }
        if (chosen == null || result.error != null) return
        usbSelectionTargetId = chosen.id
        val fresh = result.songs.associateBy { it.id }
        usbSelection.keys.toList().forEach { id ->
            if (fresh[id] != usbSelection[id]) usbSelection.remove(id)
        }
    }

    private fun persistSelection() {
        val saved = JSONArray(usbSelection.values.map { song -> JSONObject()
            .put("id", song.id).put("name", song.name).put("uri", song.sourceUri ?: JSONObject.NULL)
            .put("path", song.path ?: JSONObject.NULL).put("size", song.size)
            .put("modified", song.lastModified).put("encrypted", song.encrypted)
        })
        getSharedPreferences("ui_state", MODE_PRIVATE).edit()
            .putStringSet("phone_selection", phoneSelection.toSet())
            .putString("usb_selection", saved.toString()).putString("usb_selection_target", usbSelectionTargetId).apply()
    }

    private fun restoreSelection() {
        val prefs = getSharedPreferences("ui_state", MODE_PRIVATE)
        phoneSelection.addAll(prefs.getStringSet("phone_selection", emptySet()).orEmpty())
        runCatching {
            val saved = JSONArray(prefs.getString("usb_selection", "[]"))
            usbSelectionTargetId = prefs.getString("usb_selection_target", null)
            for (index in 0 until saved.length()) {
                val item = saved.getJSONObject(index)
                val song = SongRef(item.getString("id"), item.getString("name"),
                    if (item.isNull("uri")) null else item.getString("uri"),
                    if (item.isNull("path")) null else item.getString("path"), item.getLong("size"),
                    item.getLong("modified"), item.optBoolean("encrypted", false))
                usbSelection[song.id] = song
            }
        }.onFailure { clearUsbSelection() }
        if (usbSelectionTargetId == null) usbSelection.clear()
    }

    private fun showFailures(value: TransferSnapshot) {
        if (!resumed) return
        val detail = value.failures.take(20).joinToString("\n\n") { "${it.song.name}\n${it.reason}" } +
            if (value.failures.size > 20) "\n\n还有 ${value.failures.size - 20} 首，可一起重试。" else ""
        val dialog = AlertDialog.Builder(this).setTitle("${value.failures.size} 首歌曲未完成")
            .setMessage(detail).setPositiveButton("重试失败歌曲") { _, _ -> retryFailures() }
            .setNegativeButton("知道了", null).create()
        showLarge(dialog)
    }

    private fun showSettings() {
        val content = column().apply { setPadding(dp(20), dp(8), dp(20), dp(16)) }
        content.addView(label("歌曲文件夹", 20, INK, true))
        val path = if (settings.sourceTreeUri != null) "已授权的手机歌曲文件夹" else settings.downloadPath
        content.addView(label(path, 15, MUTED), margins(MATCH, WRAP, top = 6, bottom = 8))
        val dialog = AlertDialog.Builder(this).setTitle("设置").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("完成", null).create()
        content.addView(action("选择手机歌曲文件夹", false) { dialog.dismiss(); choosePhone() }.apply { isEnabled = !busy }, margins(MATCH, dp(56), bottom = 8))
        content.addView(action("选择 USB 根目录", false) { dialog.dismiss(); chooseUsb() }.apply { isEnabled = !busy }, margins(MATCH, dp(56), bottom = 8))
        if (!hasFileAccess()) content.addView(action("授权读取文件", false) { dialog.dismiss(); requestFileAccess() }.apply { isEnabled = !busy }, margins(MATCH, dp(56), bottom = 8))
        content.addView(label("手机歌曲始终保留；USB 中同名歌曲默认覆盖。", 16, MUTED), margins(MATCH, WRAP, top = 6, bottom = 18))
        content.addView(action("检查更新", false) { dialog.dismiss(); checkUpdate() }.apply { isEnabled = !busy }, margins(MATCH, dp(56), bottom = 8))
        content.addView(label("版本 ${BuildConfig.VERSION_NAME} · KGM 内核 1.0", 15, MUTED), margins(MATCH, WRAP, bottom = 6))
        content.addView(action("关于", false) { showAbout() }, margins(MATCH, dp(52), bottom = 8))

        showLarge(dialog)
    }

    private fun showAbout() {
        val dialog = AlertDialog.Builder(this).setTitle("音乐转移 ${BuildConfig.VERSION_NAME}")
            .setMessage("歌曲在手机本地解密和转换，不上传音频。\n\nKGM 内核 1.0 基于 OpenConverter（Apache 2.0）。支持 KGM / KGMA 的旧版 v3 type 1；不支持 KGG。\n\n音频转换使用 FFmpegKit 8.1.7，保留 LGPL 与第三方授权。\n\n输出格式：MP3、FLAC、WAV、M4A。\n更新通过本软件同签名 APK 安装。")
            .setPositiveButton("知道了", null)
            .setNeutralButton("开源许可") { _, _ -> showLicenses() }
            .setNegativeButton("开源来源") { _, _ -> openWeb("https://github.com/nowa277/OpenConverter") }.create()
        showLarge(dialog)
    }

    private fun showLicenses() {
        val files = runCatching { assets.list("licenses")?.toList().orEmpty() }.getOrDefault(emptyList())
            .sortedWith(compareBy<String> { if (it == "THIRD-PARTY-NOTICES.txt") 0 else 1 }.thenBy { it })
        if (files.isEmpty()) { message("开源许可", "许可文件无法读取，请查看随附源代码中的 third_party 目录。"); return }
        val titles = files.map { filename -> when (filename) {
            "THIRD-PARTY-NOTICES.txt" -> "第三方组件与来源说明"
            "OpenConverter-NOTICE.txt" -> "OpenConverter 来源与修改说明"
            "OpenConverter-Apache-2.0.txt" -> "OpenConverter / Apache 2.0"
            "FFmpegKit-license.txt" -> "FFmpegKit / LGPL v3"
            "GNU-GPL-3.0.txt" -> "GNU GPL v3（LGPL 引用全文）"
            else -> filename.removePrefix("FFmpegKit-license_").removeSuffix(".txt") + " 授权全文"
        } }
        val dialog = AlertDialog.Builder(this).setTitle("开源许可")
            .setItems(titles.toTypedArray()) { _, index ->
                val text = runCatching { assets.open("licenses/${files[index]}").bufferedReader().use { it.readText() } }
                    .getOrElse { "无法读取授权文件，请查看随附源代码。" }
                val body = label(text, 16, INK).apply {
                    setTextIsSelectable(true)
                    setPadding(dp(20), dp(12), dp(20), dp(16))
                }
                val content = ScrollView(this).apply { addView(body) }
                val viewer = AlertDialog.Builder(this).setTitle(titles[index]).setView(content)
                    .setPositiveButton("返回许可列表") { _, _ -> showLicenses() }.setNegativeButton("关闭", null).create()
                showLarge(viewer)
            }.setPositiveButton("关闭", null).create()
        showLarge(dialog)
    }

    private fun choosePhone() {
        if (busy) { message("正在处理任务", "任务结束后再更改文件夹。"); return }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            settings.sourceTreeUri?.let(Uri::parse) ?: DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:kgmusic/download/kgmusic"))
        runCatching { startActivityForResult(intent, PHONE_TREE) }
            .onFailure { message("无法打开文件夹选择器", "请在设置中授权读取文件，再重新选择手机歌曲文件夹。") }
    }

    private fun chooseUsb() {
        if (busy) { message("正在处理任务", "任务结束后再更改 USB。"); return }
        val available = runCatching { storage.usbTargets() }.getOrDefault(emptyList())
        if (available.isEmpty()) {
            clearUsbSelection()
            renderSongs()
            message("请插入 USB", "插入 U 盘后重新点「选择 USB」。如果系统已识别 U 盘，也可以直接授权根目录。", "选择根目录") { chooseUsbTree() }
            return
        }
        val options = available.map { "${it.label} · USB 根目录" } + "通过系统文件夹选择器授权 USB"
        val dialog = AlertDialog.Builder(this).setTitle("选择 USB")
            .setItems(options.toTypedArray()) { _, position ->
                if (busy) return@setItems
                if (position == available.size) chooseUsbTree()
                else runCatching {
                    val chosen = available[position]
                    storage.selectUsb(chosen)
                    if (target?.id != chosen.id) {
                        clearUsbSelection()
                        usb = ScanResult(emptyList())
                    }
                    target = chosen
                    invalidateScan()
                    renderSongs()
                    refreshSongs()
                }.onFailure { clearUsbSelection(); renderSongs(); message("无法选择 USB", it.message ?: "请重新插入 U 盘并授权根目录。") }
            }.setNegativeButton("取消", null).create()
        showLarge(dialog)
    }

    private fun chooseUsbTree() {
        if (busy) return
        message("选择 USB 根目录", "在接下来的文件夹选择器中，打开 U 盘，停留在最外层，再点「使用此文件夹」。", "继续") {
            if (busy) return@message
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            runCatching { startActivityForResult(intent, USB_TREE) }
                .onFailure { message("无法选择 USB", "请插入 U 盘，并在设置中授权读取文件。") }
        }
    }

    @Deprecated("Native Activity result API retained to avoid another UI dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || requestCode !in listOf(PHONE_TREE, USB_TREE)) return
        if (busy) { message("正在处理任务", "任务结束后再更改文件夹或 USB。"); return }
        val uri = data?.data ?: return
        runCatching {
            if (data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } else {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (requestCode == PHONE_TREE) storage.setPhoneTree(uri) else storage.setUsbTree(uri)
            if (requestCode == PHONE_TREE) phoneSelection.clear() else clearUsbSelection()
            if (requestCode == USB_TREE) { target = null; usb = ScanResult(emptyList()) }
            invalidateScan()
            renderSongs()
            observePhone()
            refreshSongs()
        }.onFailure {
            if (requestCode == USB_TREE) clearUsbSelection()
            renderSongs()
            message("授权未完成", it.message ?: "请重新选择文件夹。")
        }
    }

    private fun hasFileAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestFileAccess() {
        if (busy) return
        if (Build.VERSION.SDK_INT >= 30) {
            message("首次授权", "允许「音乐转移」管理文件，即可自动读取酷狗下载目录和 U 盘。授权完成后返回本软件。", "去授权") {
                if (busy) return@message
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                runCatching { startActivity(intent) }.onFailure {
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        .onFailure { choosePhone() }
                }
            }
        } else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION) {
            refreshSongs()
            observePhone()
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED })
                message("尚未授权", "也可以通过系统文件夹选择器，分别授权手机歌曲文件夹和 USB。", "选择歌曲文件夹") { choosePhone() }
        }
    }

    @Suppress("DEPRECATION")
    private fun observePhone() {
        observer?.stopWatching()
        observer = null
        if (!resumed || settings.sourceTreeUri != null || !hasFileAccess()) return
        val directory = File(settings.downloadPath)
        if (!directory.isDirectory) return
        observer = object : FileObserver(directory.absolutePath,
            FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.DELETE_SELF or FileObserver.CREATE) {
            override fun onEvent(event: Int, path: String?) { main.post { scheduleScan(1_800) } }
        }.apply { startWatching() }
    }

    private fun scheduleScan(delay: Long) { main.removeCallbacks(scheduledScan); main.postDelayed(scheduledScan, delay) }

    private fun checkUpdate() {
        val waiting = AlertDialog.Builder(this).setTitle("正在检查更新")
            .setMessage("请稍候…").setCancelable(false).create()
        showLarge(waiting)
        updates.check { result ->
            waiting.dismiss()
            if (isDestroyed) return@check
            result.onSuccess { release ->
                if (release == null) message("已是最新版本", "当前版本为 ${BuildConfig.VERSION_NAME}。")
                else {
                    val description = release.description.take(2500).ifBlank { "有新的版本可用，更新后会保留设置。" }
                    val dialog = AlertDialog.Builder(this).setTitle("发现新版本：${release.title}")
                        .setMessage(description).setPositiveButton("下载更新") { _, _ -> downloadUpdate(release) }
                        .setNegativeButton("稍后", null).setNeutralButton("查看发布页") { _, _ -> openWeb(release.pageUrl) }.create()
                    showLarge(dialog)
                }
            }.onFailure { message("暂时无法检查更新", readableFailure(it)) }
        }
    }

    private fun downloadUpdate(release: UpdateManager.Release) {
        val progress = AlertDialog.Builder(this).setTitle("正在下载更新")
            .setMessage("下载完成后会校验文件和签名。\n0%").setCancelable(false).create()
        showLarge(progress)
        updates.download(release, { percent -> if (!isDestroyed) progress.setMessage("下载完成后会校验文件和签名。\n$percent%") }) { result ->
            progress.dismiss()
            if (isDestroyed) return@download
            result.onSuccess { file ->
                if (!updates.canInstall()) {
                    message("允许安装更新", "更新文件已校验。请允许本软件安装应用，然后返回，由系统确认安装。", "去设置") {
                        pendingInstall = true
                        updates.markWaitingForInstallPermission()
                        runCatching { startActivity(updates.installationPermissionIntent()) }
                            .onFailure { message("无法打开安装权限", "请在系统设置中允许「音乐转移」安装应用。") }
                    }
                } else installUpdate(file)
            }.onFailure { message("更新未完成", readableFailure(it)) }
        }
    }

    private fun installUpdate(file: File) {
        runCatching { startActivity(updates.installationIntent(file)); updates.clearReady() }
            .onFailure { message("无法安装更新", readableFailure(it)) }
    }

    private fun readableFailure(failure: Throwable): String = when (failure) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "网络暂时不可用，请检查联网后重试。歌曲转换仍可离线使用。"
        else -> failure.message ?: "请稍后重试。"
    }

    private fun openWeb(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { message("无法打开网页", "请安装浏览器后再试。") } }

    private fun message(title: String, text: String, positive: String = "知道了", callback: (() -> Unit)? = null) {
        if (isDestroyed || isFinishing) return
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(text)
            .setPositiveButton(positive) { _, _ -> callback?.invoke() }.apply {
                if (callback != null) setNegativeButton("取消", null)
            }.create()
        showLarge(dialog)
    }

    private fun showLarge(dialog: AlertDialog) {
        if (isDestroyed || isFinishing) return
        openDialogs.add(dialog)
        dialog.setOnDismissListener { openDialogs.remove(dialog) }
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.textSize = 18f
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply {
                textSize = 17f
                minHeight = dp(52)
                val destructive = text.toString() == "删除"
                background = buttonBackground(if (destructive) ROSE else SOFT, if (destructive) ROSE_PRESSED else SOFT_PRESSED, 11)
                setTextColor(buttonTextColors(if (destructive) DANGER else TEAL))
                stateListAnimator = null
            }
        }
    }

    private inner class SongsAdapter : BaseAdapter() {
        override fun getCount() = (if (phoneTab) phone.songs else usb.songs).size
        override fun getItem(position: Int) = (if (phoneTab) phone.songs else usb.songs)[position]
        override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? SongRow) ?: SongRow()
            val song = getItem(position)
            val selected = if (phoneTab) phoneSelection.contains(song.id) else usbSelection.containsKey(song.id)
            row.title.text = song.name
            row.subtitle.text = "${if (song.encrypted) "酷狗加密" else song.name.substringAfterLast('.', "音乐").uppercase(Locale.ROOT)} · ${sizeText(song.size)}"
            row.circle.visibility = View.VISIBLE
            row.circle.checked = selected
            row.circle.isEnabled = !busy
            row.background = buttonBackground(if (selected) SELECTED else WHITE, SELECTED_PRESSED, 14)
            row.isActivated = selected
            row.contentDescription = song.name + (if (phoneTab) "，手机歌曲" else "，USB 歌曲") +
                if (selected) "，已选择，点按取消选择" else "，未选择，点按选择"
            row.isEnabled = !busy
            return row
        }
    }

    private inner class SongRow : LinearLayout(this@MainActivity) {
        val title = label("", if (compactLayout) 18 else 19, INK, true).apply { maxLines = if (compactLayout) 1 else 2; ellipsize = TextUtils.TruncateAt.END }
        val subtitle = label("", 15, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        val circle = CircleChoice()
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (compactLayout) 76 else 86)
            setPadding(dp(if (compactLayout) 12 else 16), dp(if (compactLayout) 10 else 14), dp(if (compactLayout) 12 else 14), dp(if (compactLayout) 10 else 14))
            val words = column()
            words.addView(title)
            words.addView(subtitle)
            addView(words, LayoutParams(0, WRAP, 1f))
            addView(circle, LayoutParams(dp(44), dp(44)).apply { marginStart = dp(12) })
            layoutParams = android.widget.AbsListView.LayoutParams(MATCH, WRAP)
        }
    }

    private inner class CircleChoice : View(this@MainActivity) {
        var checked = false
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val checkPath = Path()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val x = width / 2f
            val y = height / 2f
            val radius = dp(13).toFloat()
            paint.color = if (!isEnabled) DISABLED_TEXT else if (checked) TEAL else BORDER
            paint.style = if (checked) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            canvas.drawCircle(x, y, radius, paint)
            if (checked) {
                paint.color = WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2.5f).toFloat()
                paint.strokeCap = Paint.Cap.ROUND
                checkPath.reset()
                checkPath.moveTo(x - dp(6), y)
                checkPath.lineTo(x - dp(1), y + dp(5))
                checkPath.lineTo(x + dp(7), y - dp(5))
                canvas.drawPath(checkPath, paint)
            }
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun action(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 19f
        isAllCaps = false
        minHeight = dp(if (compactLayout) 48 else 52)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(buttonTextColors(if (primary) WHITE else TEAL))
        background = buttonBackground(if (primary) TEAL else SOFT, if (primary) TEAL_PRESSED else SOFT_PRESSED, 13)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }
    private fun rounded(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }
    private fun buttonBackground(color: Int, pressed: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), rounded(DISABLED_BG, radius))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radius))
        addState(intArrayOf(android.R.attr.state_focused), rounded(pressed, radius))
        addState(intArrayOf(), rounded(color, radius))
    }
    private fun buttonTextColors(color: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(DISABLED_TEXT, color))
    private fun margins(width: Int, height: Int, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(width, height).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024))
        bytes > 0 -> "${bytes / 1024} KB"
        else -> "大小未知"
    }

    companion object {
        private const val PHONE_TREE = 100
        private const val USB_TREE = 101
        private const val STORAGE_PERMISSION = 102
        private const val NOTIFICATION_PERMISSION = 103
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val BACKGROUND = Color.rgb(245, 247, 246)
        private val WHITE = Color.WHITE
        private val SOFT = Color.rgb(232, 239, 236)
        private val INK = Color.rgb(31, 47, 43)
        private val MUTED = Color.rgb(100, 117, 109)
        private val TEAL = Color.rgb(22, 119, 107)
        private val TEAL_PRESSED = Color.rgb(13, 83, 74)
        private val SOFT_PRESSED = Color.rgb(203, 221, 213)
        private val DISABLED_BG = Color.rgb(229, 232, 230)
        private val DISABLED_TEXT = Color.rgb(141, 149, 145)
        private val ROSE = Color.rgb(247, 229, 226)
        private val ROSE_PRESSED = Color.rgb(226, 196, 191)
        private val DANGER = Color.rgb(149, 52, 45)
        private val BORDER = Color.rgb(181, 197, 188)
        private val SELECTED = Color.rgb(227, 244, 237)
        private val SELECTED_PRESSED = Color.rgb(193, 222, 210)
    }
}
