package com.kgx2mp3.core

import org.junit.Assert.*
import org.junit.Test

class FlacStreamInfoTest {
    @Test fun parsesDigestAndFactsWithBothMetadataLastFlags() {
        val digest = "0123456789abcdef0123456789abcdef"
        for (last in listOf(false, true)) {
            val parsed = requireNotNull(FlacStreamInfo.parse(header(44100, 2, 16, 123456789L, digest, last)))
            assertEquals(44100, parsed.sampleRate)
            assertEquals(2, parsed.channels)
            assertEquals(16, parsed.bitsPerSample)
            assertEquals(123456789L, parsed.totalSamples)
            assertEquals(digest, parsed.md5Hex)
            assertTrue(parsed.hasMd5)
        }
    }

    @Test fun preservesUnsignedPackedValuesAndUnknownSampleCount() {
        val parsed = requireNotNull(FlacStreamInfo.parse(header(1048575, 8, 32, 0xFFFFFFFFFL)))
        assertEquals(1048575, parsed.sampleRate)
        assertEquals(8, parsed.channels)
        assertEquals(32, parsed.bitsPerSample)
        assertEquals(0xFFFFFFFFFL, parsed.totalSamples)
        assertFalse(parsed.hasMd5)
        assertEquals(0L, requireNotNull(FlacStreamInfo.parse(header(48000, 1, 24, 0))).totalSamples)
    }

    @Test fun rejectsEveryTruncatedPrefixAndInvalidMetadata() {
        val valid = header(48000, 1, 24, 100)
        for (length in 0 until 42) assertNull(FlacStreamInfo.parse(valid.copyOf(length)))
        assertNull(FlacStreamInfo.parse(valid.copyOf().also { it[0] = 0 }))
        assertNull(FlacStreamInfo.parse(valid.copyOf().also { it[4] = 1 }))
        assertNull(FlacStreamInfo.parse(valid.copyOf().also { it[7] = 33 }))
        assertNull(FlacStreamInfo.parse(valid.copyOf().also { it[8] = 0; it[9] = 15 }))
        assertNull(FlacStreamInfo.parse(header(0, 2, 16, 100)))
        assertNull(FlacStreamInfo.parse(header(48000, 2, 1, 100)))
    }

    private fun header(
        rate: Int,
        channels: Int,
        depth: Int,
        samples: Long,
        md5: String = "00000000000000000000000000000000",
        last: Boolean = false
    ): ByteArray {
        val bytes = ByteArray(42)
        "fLaC".toByteArray().copyInto(bytes)
        bytes[4] = if (last) 0x80.toByte() else 0
        bytes[7] = 34
        bytes[8] = 16; bytes[10] = 16 // fixed block size 4096
        val packed = (rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or
            ((depth - 1).toLong() shl 36) or samples
        repeat(8) { bytes[18 + it] = (packed ushr ((7 - it) * 8)).toByte() }
        md5.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(bytes, 26)
        return bytes
    }
}
