package com.kgx2mp3.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.kgx2mp3.core.FlacStreamInfo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Native tests use a runtime-pushed user's sample; copyrighted audio is never packaged in APK. */
@RunWith(AndroidJUnit4::class)
class NativeConversionTest {
    private lateinit var context: Context
    private lateinit var work: File

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        work = File(context.cacheDir, "native-validation-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup() { work.deleteRecursively() }

    @Test fun nativeLibraryLoadsAndConvertsOrdinaryAudio() {
        // This hits FFmpegKitConfig's static initializer, native ABI loading and the MP3 encoder.
        val version = FFmpegKit.executeWithArguments(arrayOf("-version"))
        assertTrue("FFmpegKit init failed: ${version.failStackTrace}", ReturnCode.isSuccess(version.returnCode))
        val source = syntheticWav("normal.wav", seconds = 2)
        val result = prepare(source, OutputFormat.MP3, "normal-mp3")
        assertAudio(result, "mp3", seconds = 2.0)
        strictDecode(result)
    }

    @Test fun realKgmConvertsAllFourOutputFormatsWithoutChangingSource() {
        val args = InstrumentationRegistry.getArguments()
        val source = File(args.getString("samplePath", "/sdcard/Download/kgx2mp3-validation/sample.kgm")!!)
        assertTrue("Push sample.kgm and grant target app file access before running this test", source.isFile && source.canRead())
        val originalHash = sha256(source)
        val expectedDuration = args.getString("expectedSeconds", "228")!!.toDouble()
        val export = args.getString("outputDir")?.let { File(it).apply { mkdirs() } }
        for (format in OutputFormat.entries) {
            val result = prepare(source, format, "real-${format.extension}")
            val codec = when (format) {
                OutputFormat.MP3 -> "mp3"
                OutputFormat.FLAC -> "flac"
                OutputFormat.WAV -> "pcm_s16le"
                OutputFormat.M4A -> "aac"
            }
            assertAudio(result, codec, expectedDuration)
            strictDecode(result)
            if (format == OutputFormat.FLAC) {
                val info = result.inputStream().use { FlacStreamInfo.parse(it.readPrefix(42)) }
                assertNotNull(info)
                assertEquals(44_100, info!!.sampleRate)
                assertEquals(2, info.channels)
                assertEquals(10_054_800L, info.totalSamples)
                val hashFile = File(work, "real-pcm.md5")
                val hash = FFmpegKit.executeWithArguments(arrayOf("-v", "error", "-noxerror", "-y", "-i", result.absolutePath,
                    "-map", "0:a:0", "-c:a", "pcm_s16le", "-f", "hash", "-hash", "md5", hashFile.absolutePath))
                assertTrue(ReturnCode.isSuccess(hash.returnCode))
                val expectedMd5 = args.getString("expectedPcmMd5", "3a98f6980c1423c19c3ba2c1daa52269")!!
                assertTrue(hashFile.isFile && hashFile.length() in 1..128)
                assertEquals("MD5=$expectedMd5", hashFile.readText(Charsets.US_ASCII).trim())
            }
            export?.let { result.copyTo(File(it, "sample.${format.extension}"), overwrite = true) }
        }
        assertEquals("The source KGM must remain unchanged", originalHash, sha256(source))
    }

    @Test fun damagedKgmIsRejectedInsteadOfProducingPartialSong() {
        val args = InstrumentationRegistry.getArguments()
        val source = File(args.getString("samplePath", "/sdcard/Download/kgx2mp3-validation/sample.kgm")!!)
        assertTrue(source.isFile && source.canRead())
        val damaged = File(work, "damaged.kgm")
        source.inputStream().use { input -> damaged.outputStream().use { output ->
            val buffer = ByteArray(65_536)
            var remaining = source.length() / 2
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
        } }
        assertThrows(IOException::class.java) { prepare(damaged, OutputFormat.MP3, "damaged-work") }
        assertFalse(File(work, "damaged-work/output.mp3").exists())
    }

    @Test fun cancelledReadDoesNotReachNativeEncoder() {
        val source = syntheticWav("cancel-read.wav", seconds = 2)
        val cancelled = AtomicBoolean(false)
        val engine = TransferEngine(context, cancelled) { stage, _ ->
            if (stage == "读取并解密") cancelled.set(true)
        }
        assertThrows(InterruptedIOException::class.java) {
            engine.prepareAudio(song(source), OutputFormat.MP3, File(work, "cancel-read-work"))
        }
        assertFalse(File(work, "cancel-read-work/output.mp3").exists())
    }

    @Test fun cancellationWhileNativeEncoderRunsDrainsSession() {
        val source = syntheticWav("cancel-native.wav", seconds = 90)
        val cancelled = AtomicBoolean(false)
        val triggered = AtomicBoolean(false)
        val engine = TransferEngine(context, cancelled) { stage, progress ->
            if (stage.startsWith("转换为") && progress > 0) {
                triggered.set(true)
                cancelled.set(true)
            }
        }
        assertThrows(InterruptedIOException::class.java) {
            engine.prepareAudio(song(source), OutputFormat.MP3, File(work, "cancel-native-work"))
        }
        assertTrue("Native statistics callback did not trigger cancellation", triggered.get())
        // A subsequent native operation must still work after draining cancellation.
        assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arrayOf("-version")).returnCode))
    }

    @Test fun metadataChangeSinceSelectionIsRejected() {
        val source = syntheticWav("changed.wav", seconds = 1)
        val selected = song(source)
        RandomAccessFile(source, "rw").use { it.setLength(it.length() - 1) }
        assertThrows(IOException::class.java) {
            TransferEngine(context, AtomicBoolean(false)) { _, _ -> }
                .prepareAudio(selected, OutputFormat.MP3, File(work, "changed-work"))
        }
    }

    private fun prepare(source: File, format: OutputFormat, folder: String): File =
        TransferEngine(context, AtomicBoolean(false)) { _, _ -> }
            .prepareAudio(song(source), format, File(work, folder))

    private fun song(source: File) = SongRef(source.absolutePath, source.name, path = source.absolutePath,
        size = source.length(), lastModified = source.lastModified(), encrypted = FilenamePolicy.encryptedName(source.name))

    private fun assertAudio(file: File, codec: String, seconds: Double) {
        assertTrue(file.isFile && file.length() > 0)
        val result = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_format", "-show_streams", "-of", "json", file.absolutePath))
        assertTrue("FFprobe failed: ${result.output}", ReturnCode.isSuccess(result.returnCode))
        val json = JSONObject(result.output)
        val streams = json.getJSONArray("streams")
        val audio = (0 until streams.length()).map { streams.getJSONObject(it) }.first { it.getString("codec_type") == "audio" }
        assertEquals(codec, audio.getString("codec_name"))
        assertEquals(seconds, json.getJSONObject("format").getString("duration").toDouble(), 0.5)
    }

    private fun strictDecode(file: File) {
        val result = FFmpegKit.executeWithArguments(arrayOf("-nostdin", "-v", "error", "-xerror", "-err_detect", "explode",
            "-i", file.absolutePath, "-map", "0:a:0", "-f", "null", "-"))
        assertTrue("Output did not decode completely: ${result.output}", ReturnCode.isSuccess(result.returnCode))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun syntheticWav(name: String, seconds: Int): File {
        val sampleRate = 44_100
        val samples = sampleRate * seconds
        val bytes = samples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes).array()
        val file = File(work, name)
        file.outputStream().buffered(65_536).use { output ->
            output.write(header)
            val frame = ByteBuffer.allocate(sampleRate * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(sampleRate) { i -> frame.putShort((kotlin.math.sin(i * 2.0 * Math.PI * 440 / sampleRate) * 8_000).toInt().toShort()) }
            repeat(seconds) { output.write(frame.array()) }
        }
        return file
    }
}
