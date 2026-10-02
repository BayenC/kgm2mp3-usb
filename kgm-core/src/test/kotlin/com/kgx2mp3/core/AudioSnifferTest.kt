package com.kgx2mp3.core

import org.junit.Assert.*
import org.junit.Test

class AudioSnifferTest {
    @Test fun detectsMp3FramesWithValidHeaderFields() {
        assertEquals(AudioFormat.MP3, AudioSniffer.detect(hex("fffb9064")))
        assertEquals(AudioFormat.MP3, AudioSniffer.detect(hex("fff39064")))
        assertNull(AudioSniffer.detect(hex("ffffffff")))
        assertNull(AudioSniffer.detect(hex("fffbf064")))
        assertNull(AudioSniffer.detect(hex("fffb9c64")))
        assertNull(AudioSniffer.detect(hex("ffeb9064")))
    }

    @Test fun detectsBothMpegVersionsAndCrcVariantsOfAdtsBeforeMp3() {
        for (second in listOf("f0", "f1", "f8", "f9")) {
            assertEquals(AudioFormat.AAC, AudioSniffer.detect(hex("ff${second}5080083ffc")))
        }
        assertNull(AudioSniffer.detect(hex("fff15080001ffc")))
        assertNull(AudioSniffer.detect(hex("fff17c80083ffc")))
    }

    @Test fun requiresValidId3AndContainerSignatures() {
        assertEquals(AudioFormat.MP3, AudioSniffer.detect(hex("49443304000000000000")))
        assertNull(AudioSniffer.detect(hex("49443305000000000000")))
        assertNull(AudioSniffer.detect(hex("49443304000080000000")))
        assertNull(AudioSniffer.detect(hex("494433")))
        assertNull(AudioSniffer.detect("RIFFxxxxxxxx".toByteArray()))
        assertNull(AudioSniffer.detect(hex("664c614300000021")))
        assertNull(AudioSniffer.detect(hex("00000000667479704d344120")))
        assertNull(AudioSniffer.detect(hex("00000020667479704e4f5045")))
    }

    @Test fun everyTruncatedPrefixAndUnknownBytesAreRejectedSafely() {
        val id3 = hex("49443304000000000000")
        for (size in 0 until id3.size) assertNull(AudioSniffer.detect(id3.copyOf(size)))
        for (size in 0..64) assertNull(AudioSniffer.detect(ByteArray(size) { 0x2A }))
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
