package com.kgx2mp3.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Random

class KgmDecoderTest {
    private val key = ByteArray(17) { if (it == 16) 0 else (it * 13 + 7).toByte() }
    private val mask = requireNotNull(javaClass.getResourceAsStream("/legacy-v3-reference-mask.bin")).use { it.readBytes() }
    private val magic = hex("7cd532eb86027f4ba8afa68e0fff9914")

    @Test fun streamingMatchesIndependentEncoderAcrossChunkBoundaries() {
        val plain = ByteArray(70001).also { Random(25577).nextBytes(it) }
        flacPrefix().copyInto(plain)
        val encrypted = encode(plain)
        for (chunkSize in listOf(1, 2, 3, 7, 17, 271, 8192, 65536)) {
            val output = ByteArrayOutputStream()
            val input = TinyReadStream(encrypted, chunkSize)
            assertEquals(AudioFormat.FLAC, KgmDecoder.decrypt(input, output, chunkSize))
            assertArrayEquals("chunk=$chunkSize", plain, output.toByteArray())
        }
    }

    @Test fun zeroLengthProviderReadsStillMakeProgress() {
        val plain = ByteArray(257) { it.toByte() }.also { flacPrefix().copyInto(it) }
        val output = ByteArrayOutputStream()
        assertEquals(AudioFormat.FLAC, KgmDecoder.decrypt(TinyReadStream(encode(plain), 3, true), output, 7))
        assertArrayEquals(plain, output.toByteArray())
    }

    @Test fun longOffsetsMatchIndependentGoldenVectors() {
        val lines = requireNotNull(javaClass.getResourceAsStream("/legacy-v3-long-offset-vectors.txt"))
            .bufferedReader().use { it.readLines() }
        for (line in lines) {
            val (offset, plain, encrypted) = line.split(':')
            val bytes = hex(encrypted)
            KgmDecoder.decryptBlock(bytes, bytes.size, offset.toLong(), key)
            assertArrayEquals("offset=$offset", hex(plain), bytes)
        }
    }

    @Test fun recognizedContainersAreDetectedAfterDecryption() {
        val cases = mapOf(
            AudioFormat.FLAC to flacPrefix(),
            AudioFormat.MP3 to hex("49443304000000000000"),
            AudioFormat.WAV to hex("524946462400000057415645"),
            AudioFormat.OGG to ("OggS".toByteArray() + ByteArray(23)),
            AudioFormat.M4A to hex("00000020667479704d34412000000200"),
            AudioFormat.AAC to hex("fff15080083ffc")
        )
        for ((format, prefix) in cases) {
            val plain = ByteArray(512).also { prefix.copyInto(it) }
            val out = ByteArrayOutputStream()
            assertEquals(format, KgmDecoder.decrypt(ByteArrayInputStream(encode(plain)), out, 1))
            assertArrayEquals(plain, out.toByteArray())
        }
    }

    @Test fun partialAndCorruptHeadersFailWithoutOutput() {
        val valid = encode(ByteArray(100).also { flacPrefix().copyInto(it) })
        for (size in listOf(0, 1, 15, 16, 43, 44, 100, 1023, 1024)) {
            assertFailsWithoutOutput(valid.copyOf(size))
        }
        assertFailsWithoutOutput(valid.copyOf().also { it[0] = 0 })
        assertFailsWithoutOutput(valid.copyOf().also { putUInt(it, 16, 43) })
        assertFailsWithoutOutput(valid.copyOf().also { putUInt(it, 16, 65537) })
        assertFailsWithoutOutput(valid.copyOf().also { putUInt(it, 16, 0xFFFFFFFFL) })
    }

    @Test fun unknownEncryptedVariantsFailWithoutOutput() {
        val valid = encode(ByteArray(100).also { flacPrefix().copyInto(it) })
        assertTrue(KgmDecoder.isEncryptedHeader(valid))
        assertFalse(KgmDecoder.isEncryptedHeader(valid.copyOf(15)))
        assertFalse(KgmDecoder.isEncryptedHeader(ByteArray(64)))
        for (version in listOf(0L, 2L, 4L, 5L, 0xFFFFFFFFL)) {
            assertFailsWithoutOutput(valid.copyOf().also { putUInt(it, 20, version) })
        }
        assertFailsWithoutOutput(valid.copyOf().also { putUInt(it, 24, 2) })
    }

    @Test fun unknownDecryptedContentIsNeverTreatedAsMp3() {
        assertFailsWithoutOutput(encode(ByteArray(1024) { 0x55 }))
        assertFailsWithoutOutput(encode(byteArrayOf(0x49, 0x44, 0x33)))
        assertFailsWithoutOutput(encode(hex("fff00000")))
        assertFailsWithoutOutput(encode(hex("664c6143")))
    }

    @Test fun cancellationPropagatesDuringHeaderAndPayloadAndDoesNotCloseStreams() {
        val plain = ByteArray(4096).also { flacPrefix().copyInto(it) }
        for (cancelAfter in listOf(1, 30, 200)) {
            val input = TinyReadStream(encode(plain), 7)
            val output = TrackingOutput()
            var checks = 0
            val cancellation = InterruptedIOException("cancelled")
            try {
                KgmDecoder.decrypt(input, output, 7) {
                    if (++checks == cancelAfter) throw cancellation
                }
                fail("Cancellation was ignored")
            } catch (actual: InterruptedIOException) {
                assertSame(cancellation, actual)
            }
            assertFalse(input.closed)
            assertFalse(output.closed)
        }
    }

    @Test fun excessiveOrNonpositiveBuffersFailBeforeReading() {
        for (size in listOf(0, -1, 1048577)) {
            try {
                KgmDecoder.decrypt(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), size)
                fail("Invalid buffer accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun assertFailsWithoutOutput(encrypted: ByteArray) {
        val output = ByteArrayOutputStream()
        try {
            KgmDecoder.decrypt(ByteArrayInputStream(encrypted), output, 7)
            fail("Malformed or unsupported input accepted")
        } catch (_: KgmDecodingException) {
            assertEquals(0, output.size())
        }
    }

    // Uses frozen, independently generated reference masks. No production
    // decryptor/helper/table is consulted while constructing the fixtures.
    private fun encode(plain: ByteArray, headerLength: Int = 1024): ByteArray {
        val header = ByteArray(headerLength)
        magic.copyInto(header)
        putUInt(header, 16, headerLength.toLong())
        putUInt(header, 20, 3)
        putUInt(header, 24, 1)
        key.copyInto(header, 28, 0, 16)
        return header + ByteArray(plain.size) { i ->
            val value = plain[i].toInt() and 0xFF
            ((value xor ((value and 15) shl 4)) xor (key[i % 17].toInt() and 0xFF) xor
                (mask[i].toInt() and 0xFF)).toByte()
        }
    }

    private fun flacPrefix() = hex("664c614300000022") + ByteArray(34)
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun putUInt(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() }
    }

    private class TinyReadStream(bytes: ByteArray, private val limit: Int, private val zeroReads: Boolean = false) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var returnZero = zeroReads
        var closed = false
        override fun read(): Int = delegate.read()
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (returnZero) { returnZero = false; return 0 }
            returnZero = zeroReads
            return delegate.read(bytes, offset, minOf(length, limit))
        }
        override fun close() { closed = true }
    }

    private class TrackingOutput : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}
