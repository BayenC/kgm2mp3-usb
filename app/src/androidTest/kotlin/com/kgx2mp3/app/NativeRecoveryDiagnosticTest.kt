package com.kgx2mp3.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.ReturnCode
import com.kgx2mp3.core.FlacStreamInfo
import com.kgx2mp3.core.KgmDecoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Reproduces CLI state after a strict failure and exports logs for the bundled native library. */
@RunWith(AndroidJUnit4::class)
class NativeRecoveryDiagnosticTest {
    @Test fun strictFailureThenExplicitResetHashAndLosslessRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val source = File(args.getString("samplePath", "/sdcard/Download/kgx2mp3-validation/sample.kgm")!!)
        assertTrue(source.isFile && source.canRead())
        val work = File(context.cacheDir, "native-diagnostic-${UUID.randomUUID()}").apply { mkdirs() }
        val report = File(args.getString("diagnosticPath", "/sdcard/Download/kgx2mp3-validation/recovery-diagnostic.txt")!!)
        report.parentFile?.mkdirs()
        val previousLevel = FFmpegKitConfig.getLogLevel()
        try {
            FFmpegKitConfig.enableRedirection()
            FFmpegKitConfig.setLogLevel(Level.AV_LOG_DEBUG)
            report.writeText("FFmpeg=${FFmpegKitConfig.getFFmpegVersion()} Kit=${FFmpegKitConfig.getVersion()}\n")
            val decoded = File(work, "decoded.flac")
            source.inputStream().use { input -> decoded.outputStream().use { output -> KgmDecoder.decrypt(input, output) } }
            val info = decoded.inputStream().use { FlacStreamInfo.parse(it.readPrefix(42)) }!!
            report.appendText("Expected PCM MD5=${info.md5Hex}, samples=${info.totalSamples}\n")
            fun execute(label: String, command: Array<String>): FFmpegSession {
                val session = FFmpegKit.executeWithArguments(command)
                val logs = session.getAllLogsAsString(10_000)
                val text = "\n--- $label ---\nargs=${command.joinToString(" ")}\ncode=${session.returnCode}; failure=${session.failStackTrace}\n$logs\n"
                report.appendText(text)
                Log.i("KgxNativeDiagnostic", "$label code=${session.returnCode} logs=${logs.length}")
                return session
            }
            fun strict(file: File) = arrayOf("-nostdin", "-loglevel", "debug", "-xerror", "-err_detect", "explode",
                "-i", file.absolutePath, "-map", "0:a:0", "-f", "null", "-")
            fun hash(file: File, output: File, reset: Boolean) =
                (listOf("-nostdin", "-loglevel", "debug", "-y") + (if (reset) listOf("-noxerror") else emptyList()) +
                    listOf("-i", file.absolutePath, "-map", "0:a:0", "-c:a", "pcm_s16le", "-f", "hash", "-hash", "md5", output.absolutePath)).toTypedArray()

            execute("strict original should fail on trailer", strict(decoded))
            val defaultHash = File(work, "default.md5")
            execute("default hash after strict failure", hash(decoded, defaultHash, false))
            report.appendText("default hash file=${if (defaultHash.exists()) defaultHash.readText() else "missing"}\n")
            val resetHash = File(work, "reset.md5")
            val resetResult = execute("explicit noxerror hash", hash(decoded, resetHash, true))
            report.appendText("reset hash file=${if (resetHash.exists()) resetHash.readText() else "missing"}\n")
            assertEquals("The explicit reset must preserve the entire source PCM", "MD5=${info.md5Hex}", resetHash.readText().trim())

            execute("strict original again to set error state", strict(decoded))
            val recovered = File(work, "recovered.flac")
            val recovery = execute("lossless encode with explicit reset and ignore_err",
                arrayOf("-nostdin", "-loglevel", "debug", "-noxerror", "-err_detect", "ignore_err", "-y",
                    "-i", decoded.absolutePath, "-map", "0:a:0", "-c:a", "flac", "-f", "flac", recovered.absolutePath))
            assertTrue("Recovery file missing; read recovery-diagnostic.txt", recovered.exists() && recovered.length() > 0)
            val clean = execute("strict recovered audio", strict(recovered))
            val recoveredInfo = recovered.inputStream().use { FlacStreamInfo.parse(it.readPrefix(42)) }!!
            val finalHash = File(work, "final.md5")
            val finalResult = execute("recovered full PCM hash", hash(recovered, finalHash, true))
            report.appendText("final hash file=${if (finalHash.exists()) finalHash.readText() else "missing"}\n")
            assertEquals(info.totalSamples, recoveredInfo.totalSamples)
            assertEquals("MD5=${info.md5Hex}", finalHash.readText().trim())
            assertTrue("Explicit reset hash exit failure, inspect report", ReturnCode.isSuccess(resetResult.returnCode))
            assertTrue("Explicit reset recovery exit failure, inspect report", ReturnCode.isSuccess(recovery.returnCode))
            assertTrue("Recovered strict decode failed, inspect report", ReturnCode.isSuccess(clean.returnCode))
            assertTrue(ReturnCode.isSuccess(finalResult.returnCode))
        } finally {
            FFmpegKitConfig.setLogLevel(previousLevel)
            work.deleteRecursively()
        }
    }
}
