package com.kgx2mp3.app

import android.content.Context
import android.annotation.SuppressLint
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.ReturnCode
import com.kgx2mp3.core.AudioSniffer
import com.kgx2mp3.core.FlacStreamInfo
import com.kgx2mp3.core.KgmDecoder
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Conservative capacity checks avoid relying on reclaiming other apps' caches.
@SuppressLint("UsableSpace")
internal class TransferEngine(
    private val context: Context,
    private val cancelled: AtomicBoolean,
    private val onProgress: (stage: String, progress: Int) -> Unit,
) {
    private val storage = StorageRepository(context)

    fun transfer(song: SongRef, target: StorageTarget, format: OutputFormat) {
        checkCancelled()
        storage.requireTarget(target)
        val work = File(context.cacheDir, "transfer-${UUID.randomUUID()}")
        if (!work.mkdirs()) throw IOException("无法创建手机临时文件。")
        try {
            val output = prepareAudio(song, format, work)
            checkCancelled()
            onProgress("复制并校验 USB", 0)
            UsbWriter(context, target, ::checkCancelled) { onProgress("复制并校验 USB", it) }
                .write(output, FilenamePolicy.outputName(song.name, format))
        } finally {
            work.deleteRecursively()
        }
    }

    /** Prepare and fully validate audio before touching USB. Caller owns the private work folder. */
    internal fun prepareAudio(song: SongRef, format: OutputFormat, work: File): File {
        checkCancelled()
        if (!storage.sourceUnchanged(song)) throw IOException("歌曲正在下载或已经变化，请刷新后重试。")
        if (context.cacheDir.usableSpace < song.size + 16_777_216)
            throw IOException("手机临时空间不足，请释放空间后重试。")
        if (!work.isDirectory && !work.mkdirs()) throw IOException("无法创建手机临时文件。")
        val raw = File(work, "decoded.bin")
        var encryptedInput = false
        onProgress("读取并解密", 0)
        storage.openSong(song).use { source ->
            val buffered = ProgressInputStream(source, song.size) { progress ->
                onProgress("读取并解密", progress)
            }.buffered(65_536)
            buffered.mark(65_536)
            val prefix = buffered.readPrefix()
            buffered.reset()
            raw.outputStream().use { output ->
                if (KgmDecoder.isEncryptedHeader(prefix)) {
                    encryptedInput = true
                    KgmDecoder.decrypt(buffered, output, checkCancelled = ::checkCancelled)
                } else if (song.encrypted || FilenamePolicy.encryptedName(song.name)) {
                    throw IOException("不是受支持的 KGM／KGMA 文件，可能未下载完整。")
                } else copy(buffered, output)
                output.fd.sync()
            }
        }
        checkCancelled()
        if (!storage.sourceUnchanged(song)) throw IOException("歌曲在读取时发生变化，请下载完成后重试。")
        if (raw.length() <= 0) throw IOException("解密后没有音频数据。")
        val detected = raw.inputStream().use { AudioSniffer.detect(it.readPrefix(8_192)) }
        var decoded = File(work, "decoded.${detected?.extension ?: "audio"}")
        if (!raw.renameTo(decoded)) throw IOException("无法准备临时音频。")
        onProgress("检查音频", 0)
        var info = probe(decoded)
        var integrityProven = false
        try {
            validateDecode(decoded)
        } catch (e: InterruptedIOException) { throw e }
        catch (e: IOException) {
            if (!encryptedInput || detected?.extension != "flac") throw e
            // Certain KGM files append nonaudio bytes. Recover only after the full decoded PCM
            // agrees with the original FLAC's own nonzero integrity hash; corruption still fails.
            decoded = recoverVerifiedFlac(decoded, work)
            integrityProven = true
            info = probe(decoded)
        }
        if (!integrityProven && detected?.extension == "flac") {
            val stream = decoded.inputStream().use { FlacStreamInfo.parse(it.readPrefix(42)) }
            if (stream != null && stream.hasMd5 && stream.totalSamples > 0 &&
                stream.bitsPerSample in setOf(8, 16, 24, 32) &&
                pcmHash(decoded, stream.bitsPerSample) != stream.md5Hex.lowercase())
                throw IOException("歌曲完整性校验未通过，请重新下载。")
        }
        val output = if (info.matches(format)) decoded else {
            val converted = File(work, "output.${format.extension}")
            onProgress("转换为 ${format.displayName}", 0)
            val args = mutableListOf("-nostdin", "-hide_banner", "-v", "error", "-xerror", "-y",
                "-i", decoded.absolutePath, "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "0")
            args += when (format) {
                OutputFormat.MP3 -> listOf("-c:a", "libmp3lame", "-b:a", "192k", "-id3v2_version", "3")
                OutputFormat.FLAC -> listOf("-c:a", "flac", "-compression_level", "5")
                OutputFormat.WAV -> listOf("-c:a", "pcm_s16le")
                OutputFormat.M4A -> listOf("-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            }
            args += converted.absolutePath
            ffmpeg(args.toTypedArray(), info.durationMs) { onProgress("转换为 ${format.displayName}", it) }
            val convertedInfo = probe(converted)
            if (!convertedInfo.matches(format)) throw IOException("转换后的音频格式不正确。")
            // Tolerate normal encoder frame padding, reject material truncation.
            if (info.durationMs > 0 && convertedInfo.durationMs > 0 &&
                kotlin.math.abs(info.durationMs - convertedInfo.durationMs) > maxOf(2_000, info.durationMs / 100))
                throw IOException("转换后的歌曲时长不完整。")
            onProgress("校验转换结果", 0)
            validateDecode(converted)
            converted
        }
        checkCancelled()
        return output
    }

    private fun probe(file: File): AudioInfo {
        val session = ffprobe(arrayOf("-v", "error", "-show_format", "-show_streams", "-of", "json", file.absolutePath))
        val json = runCatching { JSONObject(session.output) }.getOrElse { throw IOException("无法识别歌曲音频格式。") }
        val streams = json.optJSONArray("streams") ?: throw IOException("歌曲中没有音频。")
        var audio: JSONObject? = null
        for (i in 0 until streams.length()) {
            val item = streams.getJSONObject(i)
            if (item.optString("codec_type") == "audio") { audio = item; break }
        }
        val stream = audio ?: throw IOException("歌曲中没有可播放的音轨。")
        val format = json.optJSONObject("format") ?: throw IOException("音频容器不完整。")
        val duration = format.optString("duration").toDoubleOrNull()
            ?: stream.optString("duration").toDoubleOrNull() ?: 0.0
        if (duration.isNaN() || duration.isInfinite() || file.length() <= 0) throw IOException("歌曲音频损坏。")
        return AudioInfo(format.optString("format_name"), stream.optString("codec_name"), (duration * 1_000).toLong())
    }

    private fun validateDecode(file: File) {
        ffmpeg(arrayOf("-nostdin", "-hide_banner", "-v", "error", "-xerror", "-err_detect", "explode",
            "-i", file.absolutePath, "-map", "0:a:0", "-vn", "-sn", "-dn", "-f", "null", "-"), 0) {}
    }

    private fun recoverVerifiedFlac(original: File, work: File): File {
        onProgress("检查歌曲完整性", 0)
        val expected = original.inputStream().use { FlacStreamInfo.parse(it.readPrefix(42)) }
            ?: throw IOException("歌曲完整性校验未通过。")
        if (!expected.hasMd5 || expected.totalSamples <= 0 || expected.bitsPerSample !in setOf(8, 16, 24, 32))
            throw IOException("歌曲没有可验证的完整音频校验信息。")
        if (pcmHash(original, expected.bitsPerSample) != expected.md5Hex.lowercase())
            throw IOException("歌曲完整性校验未通过，请重新下载。")
        checkCancelled()
        if (context.cacheDir.usableSpace < original.length() + 16_777_216)
            throw IOException("手机临时空间不足，请释放空间后重试。")
        val recovered = File(work, "verified.flac")
        // A stream-copy remux keeps the opaque trailer. FLAC reencoding is lossless and the
        // following checks prove that it preserves the whole original PCM before USB is touched.
        ffmpeg(arrayOf("-nostdin", "-hide_banner", "-v", "error", "-noxerror", "-err_detect", "ignore_err", "-y", "-i", original.absolutePath,
            "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "0", "-c:a", "flac",
            "-f", "flac", recovered.absolutePath), 0) {}
        validateDecode(recovered)
        val actual = recovered.inputStream().use { FlacStreamInfo.parse(it.readPrefix(42)) }
            ?: throw IOException("修复后的音频校验失败。")
        if (actual.totalSamples != expected.totalSamples || actual.channels != expected.channels ||
            actual.sampleRate != expected.sampleRate ||
            pcmHash(recovered, expected.bitsPerSample) != expected.md5Hex.lowercase())
            throw IOException("修复后的音频与原歌曲不一致。")
        return recovered
    }

    private fun pcmHash(file: File, bitsPerSample: Int): String {
        val codec = when (bitsPerSample) {
            8 -> "pcm_s8"
            16 -> "pcm_s16le"
            24 -> "pcm_s24le"
            32 -> "pcm_s32le"
            else -> throw IOException("无法验证该歌曲的音频精度。")
        }
        // FFmpegKit's Android log redirection does not capture the hash muxer's stdout output.
        val hashFile = File(file.parentFile, "pcm-${UUID.randomUUID()}.md5")
        try {
            // FFmpegKit8.1.7 keeps thread-local exit_on_error across invocations. Always reset it
            // for integrity hashing after a strict decode; the entire PCM MD5 remains mandatory.
            ffmpeg(arrayOf("-nostdin", "-hide_banner", "-v", "error", "-noxerror", "-y", "-i", file.absolutePath,
                "-map", "0:a:0", "-vn", "-sn", "-dn", "-c:a", codec, "-f", "hash", "-hash", "md5",
                hashFile.absolutePath), 0) {}
            checkCancelled()
            if (!hashFile.isFile || hashFile.length() !in 1..128)
                throw IOException("无法取得歌曲完整性校验结果。")
            return Regex("^MD5=([a-fA-F0-9]{32})$").matchEntire(hashFile.readText(Charsets.US_ASCII).trim())
                ?.groupValues?.get(1)?.lowercase() ?: throw IOException("无法取得歌曲完整性校验结果。")
        } finally { hashFile.delete() }
    }

    private fun ffmpeg(arguments: Array<String>, durationMs: Long, progress: (Int) -> Unit): FFmpegSession {
        checkCancelled()
        val complete = CountDownLatch(1)
        val done = AtomicReference<FFmpegSession>()
        val started = FFmpegKit.executeWithArgumentsAsync(arguments, { session ->
            done.set(session); complete.countDown()
        }, null, { stats ->
            if (durationMs > 0) progress(((stats.time * 100) / durationMs).coerceIn(0.0, 99.0).toInt())
        })
        await(complete, started.sessionId)
        val session = done.get() ?: throw IOException("音频转换未完成。")
        if (!ReturnCode.isSuccess(session.returnCode)) throw IOException(
            if (context.cacheDir.usableSpace < 1_048_576) "手机临时空间不足。" else "音频损坏或转换失败。",
            IOException("FFmpeg returnCode=${session.returnCode}; output=${session.getAllLogsAsString(10_000).takeLast(8_192)}; failure=${session.failStackTrace}"))
        return session
    }

    private fun ffprobe(arguments: Array<String>): FFprobeSession {
        checkCancelled()
        val complete = CountDownLatch(1)
        val done = AtomicReference<FFprobeSession>()
        val started = FFprobeKit.executeWithArgumentsAsync(arguments) { session ->
            done.set(session); complete.countDown()
        }
        await(complete, started.sessionId)
        val session = done.get() ?: throw IOException("音频检查未完成。")
        if (!ReturnCode.isSuccess(session.returnCode)) throw IOException("歌曲损坏或格式不受支持。")
        return session
    }

    private fun await(latch: CountDownLatch, sessionId: Long) {
        var cancelling = false
        var interrupted = false
        while (true) {
            val finished = try { latch.await(150, TimeUnit.MILLISECONDS) }
            catch (e: InterruptedException) { interrupted = true; false }
            if (finished) break
            if (!cancelling && (cancelled.get() || interrupted || Thread.currentThread().isInterrupted)) {
                cancelling = true
                FFmpegKit.cancel(sessionId)
            }
        }
        // The native session has closed its files before local cleanup, including thread interruption.
        if (interrupted) Thread.currentThread().interrupt()
        if (cancelling || interrupted) throw InterruptedIOException("已取消")
        checkCancelled()
    }

    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("已取消")
    }

    private fun copy(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(65_536)
        while (true) {
            checkCancelled()
            val n = input.read(buffer)
            if (n < 0) return
            if (n > 0) output.write(buffer, 0, n)
        }
    }

    private data class AudioInfo(val container: String, val codec: String, val durationMs: Long) {
        fun matches(format: OutputFormat): Boolean = when (format) {
            OutputFormat.MP3 -> container.split(',').contains("mp3") && codec == "mp3"
            OutputFormat.FLAC -> container.split(',').contains("flac") && codec == "flac"
            OutputFormat.WAV -> container.split(',').contains("wav") && codec == "pcm_s16le"
            OutputFormat.M4A -> container.split(',').any { it in setOf("m4a", "mp4", "mov") } && codec == "aac"
        }
    }

    private inner class ProgressInputStream(input: InputStream, private val total: Long,
        private val progress: (Int) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int { checkCancelled(); return super.read().also { if (it >= 0) track(1) } }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            return `in`.read(buffer, offset, length).also { if (it > 0) track(it) }
        }
        private fun track(n: Int) { count += n; progress(if (total <= 0) 0 else (count * 100 / total).coerceIn(0, 100).toInt()) }
    }
}
