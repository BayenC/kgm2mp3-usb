package com.kgx2mp3.core

import java.io.File
import java.security.MessageDigest

/** Explicit opt-in validation; no copyrighted sample is stored in test fixtures. */
object RealSampleValidation {
    @JvmStatic fun main(args: Array<String>) {
        val inputPath = requireNotNull(System.getenv("KGM_SAMPLE")) { "Set KGM_SAMPLE to the full sample path" }
        val outputPath = requireNotNull(System.getenv("KGM_OUTPUT")) { "Set KGM_OUTPUT to a temporary decoded-audio path" }
        val input = File(inputPath).canonicalFile
        val output = File(outputPath).canonicalFile
        require(input != output) { "Output must not replace the encrypted input" }
        require(!output.exists()) { "Output already exists; choose a new temporary path" }
        val digest = MessageDigest.getInstance("SHA-256")
        val format = try {
            input.inputStream().buffered().use { encrypted ->
                output.outputStream().buffered().use { decoded ->
                    KgmDecoder.decrypt(encrypted, decoded)
                }
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
        output.inputStream().use { decoded ->
            val bytes = ByteArray(65536)
            while (true) {
                val count = decoded.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        println("Detected container: ${format.extension}")
        println("Decoded bytes: ${output.length()}")
        println("Decoded SHA-256: ${digest.digest().joinToString("") { "%02x".format(it) }}")
        println("Decoded output: ${output.path}")
        println("Run a complete audio decode/probe before declaring sample support.")
    }
}
