package com.kgx2mp3.app

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
import android.widget.EditText
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
    private val selection = linkedSetOf<String>()
    private var phone = ScanResult(emptyList())
    private var usb = ScanResult(emptyList())
    private var target: StorageTarget? = null
    private var targets = emptyList<StorageTarget>()
    private var snapshot = TransferSnapshot()
    private var shownCompletion = ""
    private var observer: FileObserver? = null
    private var pendingInstall = false
    private var scanAfterTransfer = false
    private val openDialogs = mutableSetOf<AlertDialog>()

    private lateinit var root: LinearLayout
    private lateinit var usbTitle: TextView
    private lateinit var usbSubtitle: TextView
    private lateinit var permission: Button
    private lateinit var phoneButton: Button
    private lateinit var usbButton: Button
    private lateinit var count: TextView
    private lateinit var selectAll: Button
    private lateinit var refresh: Button
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var emptyAction: Button
    private lateinit var transfer: Button
    private lateinit var footerHint: TextView
    private lateinit var progressCard: LinearLayout
    private lateinit var progressTitle: TextView
    private lateinit var progressMessage: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cancel: Button
    private lateinit var retry: Button
    private val adapter = SongsAdapter()

    private val transferListener: (TransferSnapshot) -> Unit = { value ->
        main.post { if (!isDestroyed) renderTransfer(value) }
    }
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { scheduleScan(400) }
    }
    private val periodic = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (!snapshot.running) refreshSongs()
            main.postDelayed(this, 7_000)
        }
    }
    private val scheduledScan = Runnable { if (resumed && !snapshot.running) refreshSongs() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        settings = SettingsStore(this)
        storage = StorageRepository(this)
        updates = UpdateManager(applicationContext)
        TransferController.restoreInterrupted(this)
        phoneTab = state?.getBoolean("phoneTab", true) ?: true
        if (state?.getBoolean("restoreSelection", false) == true) {
            selection.addAll(getSharedPreferences("ui_state", MODE_PRIVATE).getStringSet("selection", emptySet()).orEmpty())
        }
        pendingInstall = state?.getBoolean("pendingInstall", false) ?: false
        shownCompletion = state?.getString("shownCompletion") ?: ""
        buildScreen()
        TransferController.addListener(transferListener)
        renderTransfer(TransferController.snapshot)
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
        unregisterReceiver(mediaReceiver)
        scanner.shutdownNow()
        updates.close()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("phoneTab", phoneTab)
        // Song IDs may contain long Chinese paths. Keep large selections out of Binder's state bundle.
        getSharedPreferences("ui_state", MODE_PRIVATE).edit().putStringSet("selection", selection.toSet()).apply()
        out.putBoolean("restoreSelection", true)
        out.putBoolean("pendingInstall", pendingInstall)
        out.putString("shownCompletion", shownCompletion)
        super.onSaveInstanceState(out)
    }

    private fun buildScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = column().apply { setBackgroundColor(BACKGROUND); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(12) + bars.bottom)
            insets
        }
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = column()
        heading.addView(label("音乐转移", 28, INK, true))
        heading.addView(label("选好歌曲，一键放进 U 盘", 16, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        header.addView(heading, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(action("设置", false) { showSettings() }, LinearLayout.LayoutParams(dp(78), dp(54)))
        root.addView(header, margins(MATCH, WRAP, bottom = 20))

        val usbCard = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(WHITE, 18)
            setPadding(dp(18), dp(16), dp(10), dp(16))
        }
        val usbWords = column()
        usbTitle = label("请插入 USB", 21, INK, true)
        usbSubtitle = label("歌曲会保存在 U 盘根目录", 16, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        usbWords.addView(usbTitle)
        usbWords.addView(usbSubtitle)
        usbCard.addView(usbWords, LinearLayout.LayoutParams(0, WRAP, 1f))
        usbCard.addView(action("选择", false) { chooseUsb() }, LinearLayout.LayoutParams(dp(72), dp(54)))
        root.addView(usbCard, margins(MATCH, WRAP, bottom = 12))

        permission = action("首次使用：授权读取音乐和 USB", false) { requestFileAccess() }.apply {
            textSize = 17f
            visibility = View.GONE
        }
        root.addView(permission, margins(MATCH, dp(58), bottom = 12))

        val tabs = row().apply { background = rounded(SOFT, 14); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        phoneButton = action("手机歌曲", false) { switchTab(true) }
        usbButton = action("USB 歌曲", false) { switchTab(false) }
        tabs.addView(phoneButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        tabs.addView(usbButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(tabs, margins(MATCH, WRAP, bottom = 8))

        val toolbar = row().apply { gravity = Gravity.CENTER_VERTICAL }
        count = label("正在读取歌曲…", 17, MUTED)
        toolbar.addView(count, LinearLayout.LayoutParams(0, WRAP, 1f))
        refresh = action("刷新", false) { refreshSongs() }.apply { textSize = 17f }
        selectAll = action("全选", false) {
            if (phone.songs.isNotEmpty() && phone.songs.all { selection.contains(it.id) }) selection.clear()
            else selection.addAll(phone.songs.map { it.id })
            renderSongs()
        }.apply { textSize = 17f }
        toolbar.addView(refresh, LinearLayout.LayoutParams(dp(66), dp(52)))
        toolbar.addView(selectAll, LinearLayout.LayoutParams(dp(76), dp(52)))
        root.addView(toolbar)

        val listContainer = android.widget.FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                if (phoneTab && !snapshot.running) {
                    val song = phone.songs[position]
                    if (!selection.add(song.id)) selection.remove(song.id)
                    renderSongs()
                }
            }
        }
        listContainer.addView(list, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        empty = column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        emptyTitle = label("正在读取歌曲…", 22, INK, true).apply { gravity = Gravity.CENTER }
        emptyMessage = label("稍等片刻", 17, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(18))
        }
        emptyAction = action("选择歌曲文件夹", false) { if (phoneTab) choosePhone() else chooseUsb() }
        empty.addView(emptyTitle)
        empty.addView(emptyMessage)
        empty.addView(emptyAction, LinearLayout.LayoutParams(MATCH, dp(56)))
        listContainer.addView(empty, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(listContainer, LinearLayout.LayoutParams(MATCH, 0, 1f))

        progressCard = column().apply {
            background = rounded(SOFT, 16)
            setPadding(dp(16), dp(14), dp(16), dp(12))
            visibility = View.GONE
        }
        progressTitle = label("正在处理", 20, INK, true)
        progressMessage = label("", 16, MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(TEAL)
        }
        val progressActions = row()
        cancel = action("取消", false) { TransferController.cancel(); cancel.isEnabled = false; cancel.text = "正在取消…" }
        retry = action("重试失败歌曲", false) { retryFailures() }.apply { visibility = View.GONE }
        progressActions.addView(retry, LinearLayout.LayoutParams(0, dp(52), 1f))
        progressActions.addView(cancel, LinearLayout.LayoutParams(0, dp(52), 1f))
        progressCard.addView(progressTitle)
        progressCard.addView(progressMessage, margins(MATCH, WRAP, top = 5))
        progressCard.addView(progressBar, margins(MATCH, dp(8), top = 12, bottom = 4))
        progressCard.addView(progressActions)
        root.addView(progressCard, margins(MATCH, WRAP, top = 8, bottom = 8))

        transfer = action("转换并转移", true) { beginTransfer() }
        root.addView(transfer, margins(MATCH, dp(64), top = 10))
        footerHint = label("默认 MP3 · 手机原文件保留", 15, MUTED).apply { gravity = Gravity.CENTER }
        root.addView(footerHint, margins(MATCH, WRAP, top = 8))
        renderSongs()
    }

    private fun switchTab(value: Boolean) { phoneTab = value; renderSongs() }

    private fun refreshSongs() {
        if (isDestroyed || scanning || snapshot.running) return
        scanning = true
        val generation = ++scanGeneration
        refresh.isEnabled = false
        scanner.execute {
            val result = runCatching {
                val phoneScan = storage.scanPhone()
                val available = storage.usbTargets()
                val chosen = storage.selectedUsb()
                val usbScan = chosen?.let(storage::scanUsb) ?: ScanResult(emptyList())
                Triple(phoneScan, Pair(chosen, available), usbScan)
            }
            main.post {
                if (isDestroyed || generation != scanGeneration) return@post
                scanning = false
                refresh.isEnabled = true
                result.onSuccess { (newPhone, usbInfo, newUsb) ->
                    phone = newPhone
                    target = usbInfo.first
                    targets = usbInfo.second
                    usb = newUsb
                    // Preserve choices across refreshes while a download is being completed.
                    selection.retainAll(phone.songs.map { it.id }.toSet())
                }.onFailure { phone = ScanResult(emptyList(), it.message ?: "暂时无法读取歌曲。") }
                renderSongs()
            }
        }
    }

    private fun renderSongs() {
        if (!::list.isInitialized) return
        val songs = if (phoneTab) phone.songs else usb.songs
        val error = if (phoneTab) phone.error else usb.error
        val selected = phone.songs.count { selection.contains(it.id) }
        phoneButton.background = rounded(if (phoneTab) WHITE else Color.TRANSPARENT, 11)
        usbButton.background = rounded(if (!phoneTab) WHITE else Color.TRANSPARENT, 11)
        phoneButton.setTextColor(if (phoneTab) TEAL else MUTED)
        usbButton.setTextColor(if (!phoneTab) TEAL else MUTED)
        phoneButton.isSelected = phoneTab
        usbButton.isSelected = !phoneTab
        count.text = if (phoneTab) "${songs.size} 首 · 已选 $selected 首" else "${songs.size} 首歌曲"
        selectAll.visibility = if (phoneTab) View.VISIBLE else View.GONE
        selectAll.isEnabled = songs.isNotEmpty() && !snapshot.running
        selectAll.text = if (phone.songs.isNotEmpty() && selected == phone.songs.size) "取消全选" else "全选"
        selectAll.textSize = if (selectAll.text.length > 2) 15f else 17f
        permission.visibility = if (!hasFileAccess() && settings.sourceTreeUri == null && !snapshot.running) View.VISIBLE else View.GONE
        val chosen = target
        usbTitle.text = if (chosen == null) "请插入 USB" else "USB 已连接"
        usbTitle.setTextColor(if (chosen == null) INK else TEAL)
        usbSubtitle.text = if (chosen == null) "插入 U 盘后，点这里选择" else {
            val available = runCatching { storage.freeSpace(chosen) }.getOrNull()
            chosen.label + (available?.let { " · 剩余 ${sizeText(it)}" } ?: " · 根目录")
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
                emptyMessage.text = "在「手机歌曲」里选好歌，再点「转换并转移」。"
                emptyAction.text = "查看手机歌曲"
            }
        }
        emptyAction.setOnClickListener {
            if (!phoneTab && chosen != null && error == null) switchTab(true)
            else if (phoneTab) choosePhone() else chooseUsb()
        }
        footerHint.text = "${settings.outputFormat.displayName} · 手机原文件保留 · 同名覆盖"
        transfer.isEnabled = chosen != null && selected > 0 && !snapshot.running
        transfer.text = if (snapshot.running) "正在转换并转移…" else "转换并转移${if (selected > 0) "（$selected 首）" else ""}"
        transfer.alpha = if (transfer.isEnabled) 1f else .48f
        adapter.notifyDataSetChanged()
    }

    private fun beginTransfer() {
        val chosen = target ?: return
        val songs = phone.songs.filter { selection.contains(it.id) }
        if (songs.isEmpty() || snapshot.running) return
        ensureNotificationPermission()
        shownCompletion = ""
        runCatching { TransferController.start(this, songs, chosen, settings.outputFormat) }
            .onFailure { message("未能开始", it.message ?: "请重新选择 USB 后再试。") }
    }

    private fun renderTransfer(value: TransferSnapshot) {
        val wasRunning = snapshot.running
        snapshot = value
        progressCard.visibility = if (value.running || value.total > 0) View.VISIBLE else View.GONE
        if (value.running) {
            progressTitle.text = "正在处理 ${value.position.coerceAtLeast(1)} / ${value.total} 首"
            progressMessage.text = "${value.stage}${if (value.currentName.isNotBlank()) " · ${value.currentName}" else ""}"
            progressBar.visibility = View.VISIBLE
            progressBar.progress = value.progress.coerceIn(0, 100)
            cancel.visibility = View.VISIBLE
            cancel.isEnabled = true
            cancel.text = "取消"
            retry.visibility = View.GONE
            scanAfterTransfer = true
        } else if (value.total > 0) {
            val unfinished = (value.total - value.successes - value.failures.size).coerceAtLeast(0)
            progressTitle.text = "成功 ${value.successes} 首${if (value.failures.isNotEmpty()) "，失败 ${value.failures.size} 首" else ""}"
            progressMessage.text = if (unfinished > 0) "已停止，剩余 $unfinished 首未处理。手机原文件仍保留。"
                else if (value.failures.isNotEmpty()) value.failures.first().let { "${it.song.name}：${it.reason}" }
                else "歌曲已放进 U 盘，可以拔出后在车上播放。"
            progressBar.visibility = View.GONE
            cancel.visibility = View.GONE
            retry.visibility = if (value.failures.isNotEmpty()) View.VISIBLE else View.GONE
            if (wasRunning || scanAfterTransfer) {
                scanAfterTransfer = false
                if (resumed) refreshSongs()
                val completionKey = "${value.total}:${value.position}:${value.successes}:${value.failures.hashCode()}"
                if (value.failures.isNotEmpty() && shownCompletion != completionKey) {
                    shownCompletion = completionKey
                    showFailures(value)
                }
            }
        }
        renderSongs()
    }

    private fun retryFailures() {
        val chosen = target ?: run { chooseUsb(); return }
        val songs = snapshot.failures.map { it.song }
        if (songs.isEmpty() || snapshot.running) return
        shownCompletion = ""
        runCatching { TransferController.start(this, songs, chosen, settings.outputFormat) }
            .onFailure { message("未能重试", it.message ?: "请检查 U 盘。") }
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
        content.addView(label("输出格式", 20, INK, true))
        content.addView(label("默认为 MP3，适合大多数车载播放器", 16, MUTED), margins(MATCH, WRAP, top = 6, bottom = 10))
        val formatRow = row()
        OutputFormat.entries.forEach { format ->
            val button = action(format.displayName, settings.outputFormat == format) {}
            button.textSize = 17f
            button.setOnClickListener {
                if (snapshot.running) { message("正在处理歌曲", "处理结束后再修改输出格式。"); return@setOnClickListener }
                settings.outputFormat = format
                (0 until formatRow.childCount).forEach { index ->
                    (formatRow.getChildAt(index) as Button).apply {
                        val active = text == format.displayName
                        background = rounded(if (active) TEAL else SOFT, 12)
                        setTextColor(if (active) WHITE else INK)
                    }
                }
                renderSongs()
            }
            formatRow.addView(button, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(4) })
        }
        content.addView(formatRow, margins(MATCH, WRAP, bottom = 18))
        content.addView(label("歌曲文件夹", 20, INK, true))
        val path = if (settings.sourceTreeUri != null) "已授权的手机歌曲文件夹" else settings.downloadPath
        content.addView(label(path, 15, MUTED), margins(MATCH, WRAP, top = 6, bottom = 8))
        val dialog = AlertDialog.Builder(this).setTitle("设置").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("完成", null).create()
        content.addView(action("选择手机歌曲文件夹", false) { dialog.dismiss(); choosePhone() }, margins(MATCH, dp(56), bottom = 8))
        content.addView(action("选择 USB 根目录", false) { dialog.dismiss(); chooseUsb() }, margins(MATCH, dp(56), bottom = 8))
        if (!hasFileAccess()) content.addView(action("授权读取文件", false) { dialog.dismiss(); requestFileAccess() }, margins(MATCH, dp(56), bottom = 8))
        content.addView(label("手机歌曲始终保留；USB 中同名歌曲默认覆盖。", 16, MUTED), margins(MATCH, WRAP, top = 6, bottom = 18))
        content.addView(action("检查更新", false) { dialog.dismiss(); checkUpdate() }, margins(MATCH, dp(56), bottom = 8))
        content.addView(label("版本 ${BuildConfig.VERSION_NAME} · KGM 内核 1.0", 15, MUTED), margins(MATCH, WRAP, bottom = 6))
        content.addView(action("关于", false) { showAbout() }, margins(MATCH, dp(52), bottom = 8))

        val advanced = column().apply { visibility = View.GONE }
        val advancedButton = action("进阶设置 ▾", false) {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }.apply { textSize = 16f }
        content.addView(advancedButton, margins(MATCH, dp(52)))
        advanced.addView(label("更新仓库（由家人配置）", 17, INK, true))
        advanced.addView(label("使用发布本软件的 GitHub 仓库，格式为 用户名/仓库名。", 15, MUTED), margins(MATCH, WRAP, top = 5))
        val repo = EditText(this).apply {
            setText(settings.updateRepo)
            textSize = 17f
            setSingleLine()
            hint = "用户名/仓库名"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(WHITE, 10, BORDER)
        }
        advanced.addView(repo, margins(MATCH, dp(56), top = 10))
        advanced.addView(action("保存更新仓库", false) {
            val value = repo.text.toString().trim().removePrefix("https://github.com/").trimEnd('/')
            if (value.isNotEmpty() && !Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}").matches(value)) {
                repo.error = "请输入 用户名/仓库名"
            } else { settings.updateRepo = value; message("已保存", if (value.isBlank()) "更新仓库已清空。" else "之后可通过「检查更新」获取已发布的版本。") }
        }, margins(MATCH, dp(54), top = 6))
        val manualPath = EditText(this).apply {
            setText(settings.downloadPath)
            textSize = 16f
            hint = "/storage/emulated/0/kgmusic/download"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        advanced.addView(label("手机目录路径", 17, INK, true), margins(MATCH, WRAP, top = 14))
        advanced.addView(manualPath, margins(MATCH, dp(56), top = 5))
        advanced.addView(action("保存手机目录", false) {
            val value = manualPath.text.toString().trim()
            if (!value.startsWith("/storage/")) manualPath.error = "请填写 /storage/ 下的文件夹路径"
            else if (snapshot.running) message("正在处理歌曲", "处理结束后再更改文件夹。")
            else {
                settings.downloadPath = value
                settings.sourceTreeUri = null
                observePhone()
                refreshSongs()
                message("已保存", "将从这个目录读取歌曲。")
            }
        }, margins(MATCH, dp(54), top = 5))
        content.addView(advanced)
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
        if (snapshot.running) { message("正在处理歌曲", "处理结束后再更改文件夹。"); return }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3Akgmusic%2Fdownload"))
        runCatching { startActivityForResult(intent, PHONE_TREE) }
            .onFailure { message("无法打开文件夹选择器", "请在设置中授权读取文件，再填写手机目录路径。") }
    }

    private fun chooseUsb() {
        if (snapshot.running) { message("正在处理歌曲", "处理结束后再更改 USB。"); return }
        val available = targets
        if (available.isEmpty()) { chooseUsbTree(); return }
        val options = available.map { "${it.label} · USB 根目录" } + "通过系统文件夹选择器授权 USB"
        val dialog = AlertDialog.Builder(this).setTitle("选择 USB")
            .setItems(options.toTypedArray()) { _, position ->
                if (position == available.size) chooseUsbTree()
                else { storage.selectUsb(available[position]); refreshSongs() }
            }.setNegativeButton("取消", null).create()
        showLarge(dialog)
    }

    private fun chooseUsbTree() {
        message("选择 USB 根目录", "在接下来的文件夹选择器中，打开 U 盘，停留在最外层，再点「使用此文件夹」。", "继续") {
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
        val uri = data?.data ?: return
        runCatching {
            if (data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } else {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (requestCode == PHONE_TREE) storage.setPhoneTree(uri) else storage.setUsbTree(uri)
            if (requestCode == PHONE_TREE) selection.clear()
            observePhone()
            refreshSongs()
        }.onFailure { message("授权未完成", it.message ?: "请重新选择文件夹。") }
    }

    private fun hasFileAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            message("首次授权", "允许「音乐转移」管理文件，即可自动读取酷狗下载目录和 U 盘。授权完成后返回本软件。", "去授权") {
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
        val repo = settings.updateRepo.trim()
        if (repo.isBlank()) {
            message("更新尚未配置", "当前版本可正常离线使用。后续发布新版时，由家人在「设置 → 进阶设置」填写本软件的更新仓库。")
            return
        }
        val waiting = AlertDialog.Builder(this).setTitle("正在检查更新")
            .setMessage("请稍候…").setCancelable(false).create()
        showLarge(waiting)
        updates.check(repo) { result ->
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
            dialog.getButton(which)?.apply { textSize = 17f; minHeight = dp(52); setTextColor(TEAL) }
        }
    }

    private inner class SongsAdapter : BaseAdapter() {
        override fun getCount() = (if (phoneTab) phone.songs else usb.songs).size
        override fun getItem(position: Int) = (if (phoneTab) phone.songs else usb.songs)[position]
        override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? SongRow) ?: SongRow()
            val song = getItem(position)
            val selected = phoneTab && selection.contains(song.id)
            row.title.text = song.name
            row.subtitle.text = "${if (song.encrypted) "酷狗加密" else song.name.substringAfterLast('.', "音乐").uppercase(Locale.ROOT)} · ${sizeText(song.size)}"
            row.circle.visibility = if (phoneTab) View.VISIBLE else View.GONE
            row.circle.checked = selected
            row.background = rounded(if (selected) SELECTED else WHITE, 14)
            row.isActivated = selected
            row.contentDescription = song.name + if (phoneTab) { if (selected) "，已选择，点按取消选择" else "，未选择，点按选择" } else "，USB 歌曲"
            row.isEnabled = !snapshot.running || !phoneTab
            return row
        }
    }

    private inner class SongRow : LinearLayout(this@MainActivity) {
        val title = label("", 19, INK, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val subtitle = label("", 15, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        val circle = CircleChoice()
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(86)
            setPadding(dp(16), dp(14), dp(14), dp(14))
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
            paint.color = if (checked) TEAL else BORDER
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
        minHeight = dp(52)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(if (primary) WHITE else TEAL)
        background = rounded(if (primary) TEAL else SOFT, 13)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }
    private fun rounded(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }
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
        private val BORDER = Color.rgb(181, 197, 188)
        private val SELECTED = Color.rgb(227, 244, 237)
    }
}
