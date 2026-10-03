package com.kgm2mp3_usb.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Public release delivery only. User audio never enters this code path. */
class UpdateManager(private val context: Context) {
    data class Release(val title: String, val description: String, val apkUrl: String,
                       val apkName: String, val checksumUrl: String, val pageUrl: String)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun check(callback: (Result<Release?>) -> Unit) {
        worker.execute {
            val result = runCatching {
                val data = String(readLimited(UPDATE_RELEASE_URL, 256 * 1024), Charsets.UTF_8)
                val release = JSONObject(data)
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@runCatching null
                val tag = release.getString("tag_name")
                if (!releaseVersionIsNewer(tag, BuildConfig.VERSION_NAME)) return@runCatching null
                val assets = release.getJSONArray("assets")
                val candidates = (0 until assets.length()).map { index ->
                    val asset = assets.getJSONObject(index)
                    UpdateAsset(asset.getString("name"), asset.getString("browser_download_url"))
                }
                val selected = selectUpdateAssets(candidates, Build.SUPPORTED_ABIS.toList())
                validatedGithubUpdateUrl(selected.apk.url)
                validatedGithubUpdateUrl(selected.checksum.url)
                val pageUrl = release.getString("html_url")
                validatedGithubUpdateUrl(pageUrl)
                Release(release.optString("name").ifBlank { tag }, release.optString("body"),
                    selected.apk.url, selected.apk.name, selected.checksum.url, pageUrl)
            }
            main.post { callback(result.mapUpdateFailure()) }
        }
    }

    fun download(release: Release, progress: (Int) -> Unit, callback: (Result<File>) -> Unit) {
        worker.execute {
            val result = runCatching {
                val checksumText = String(readLimited(release.checksumUrl, 16 * 1024)).trim()
                val expected = checksumForApk(checksumText, release.apkName)
                val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                val file = File(directory, "update.apk")
                val temporary = File(directory, "update.part")
                temporary.delete()
                val connection = open(release.apkUrl)
                try {
                    val length = connection.contentLengthLong
                    require(length <= MAX_APK) { "更新文件过大。" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    var lastPercent = -1
                    connection.inputStream.use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) error("下载已取消。")
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                require(written <= MAX_APK) { "更新文件过大。" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                val percent = if (length > 0) (written * 100 / length).toInt() else 0
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    main.post { progress(percent) }
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    require(written > 0 && (length < 0 || written == length)) { "下载不完整，请重试。" }
                    require(hex(digest.digest()) == expected) { "更新文件校验失败，请重试。" }
                    verifyPackage(temporary)
                    file.delete()
                    require(temporary.renameTo(file)) { "无法保存更新文件。" }
                    prefs.edit().putString("readyPath", file.absolutePath).apply()
                    file
                } catch (failure: Throwable) {
                    temporary.delete()
                    throw failure
                } finally { connection.disconnect() }
            }
            main.post { callback(result.mapUpdateFailure()) }
        }
    }

    fun readyUpdate(): File? = prefs.getString("readyPath", null)?.let(::File)?.takeIf { it.isFile }

    fun waitingForInstallPermission(): Boolean = prefs.getBoolean("awaitingPermission", false)
    fun markWaitingForInstallPermission() { prefs.edit().putBoolean("awaitingPermission", true).apply() }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installationPermissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"))

    /** Android's installer remains the final user confirmation. Re-check before sharing the file. */
    fun installationIntent(file: File): Intent {
        verifyPackage(file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(
            FileProvider.getUriForFile(context, "${context.packageName}.files", file),
            "application/vnd.android.package-archive"
        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun clearReady() { prefs.edit().remove("readyPath").remove("awaitingPermission").apply() }
    fun close() { worker.shutdownNow() }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("更新安装包无法识别。")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val incomingVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        verifyUpdateIdentity(UpdatePackageIdentity(installed.packageName, installedVersion, signers(installed)),
            UpdatePackageIdentity(archive.packageName, incomingVersion, signers(archive)))
        verifyNativeAbiCompatibility(nativeAbisInArchive(file), Build.SUPPORTED_ABIS.toList())
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: error("无法验证安装包签名。")
    }

    private fun readLimited(url: String, maximum: Int): ByteArray {
        val connection = open(url)
        return try {
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maximum) { "服务器返回内容过大。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    private fun open(initialUrl: String): HttpURLConnection {
        var next = initialUrl
        repeat(6) {
            val url = validatedGithubUpdateUrl(next)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "kgm2mp3-usb/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val status = try { connection.responseCode } catch (failure: Throwable) {
                connection.disconnect()
                throw failure
            }
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "更新服务器跳转无效。" }
                next = URL(url, location).toString()
            } else {
                if (status != 200) {
                    connection.disconnect()
                    error(when (status) {
                        404 -> "暂时没有可用的正式版本，请稍后再试。"
                        403, 429 -> "检查更新太频繁，请稍后再试。"
                        else -> "更新服务器暂时不可用（$status）。"
                    })
                }
                return connection
            }
        }
        error("更新服务器跳转次数过多。")
    }

    companion object {
        private const val MAX_APK = 250L * 1024 * 1024
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal fun validatedGithubUpdateUrl(value: String): URL {
    val url = URL(value)
    val hosts = setOf("api.github.com", "github.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com", "github-releases.githubusercontent.com")
    require(url.protocol == "https" && url.host.lowercase() in hosts && url.userInfo == null &&
        (url.port == -1 || url.port == 443)) { "更新地址不是可信的 GitHub HTTPS 地址。" }
    return url
}

internal fun checksumForApk(text: String, apkName: String): String = text.lineSequence().mapNotNull { line ->
    val parts = line.trim().split(Regex("\\s+"), limit = 2)
    val hash = parts.firstOrNull()?.lowercase()
    if (hash != null && Regex("[a-f0-9]{64}").matches(hash) &&
        (parts.size == 1 || parts[1].trimStart('*') == apkName)) hash else null
}.singleOrNull() ?: error("更新校验文件的内容不正确。")

internal data class UpdatePackageIdentity(val packageName: String, val versionCode: Long, val certificates: Set<String>)

internal fun verifyUpdateIdentity(installed: UpdatePackageIdentity, incoming: UpdatePackageIdentity) {
    require(incoming.packageName == installed.packageName) { "更新安装包不属于音乐转移。" }
    require(incoming.versionCode > installed.versionCode) { "已是当前版本，或更新版本较旧。" }
    require(installed.certificates.isNotEmpty() && incoming.certificates.isNotEmpty() &&
        incoming.certificates == installed.certificates) { "更新签名不匹配，已停止安装。" }
}

/** Preserve validation details while giving transport and malformed-response errors useful wording. */
private fun <T> Result<T>.mapUpdateFailure(): Result<T> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { failure ->
        val friendly = when (failure) {
            is java.net.UnknownHostException, is java.net.SocketTimeoutException,
            is java.net.ConnectException, is java.net.SocketException ->
                "网络暂时不可用，请检查联网后重试。歌曲转换仍可离线使用。"
            is javax.net.ssl.SSLException -> "无法安全连接更新服务器，请检查网络和手机时间后重试。"
            is org.json.JSONException -> "更新服务器返回的信息无法识别，请稍后再试。"
            else -> null
        }
        Result.failure(if (friendly != null) IllegalStateException(friendly, failure) else failure)
    }
)
