package com.kgm2mp3_usb.app

import org.junit.Assert.*
import org.junit.Test

class FilenamePolicyTest {
    @Test fun removesBothEncryptionAndCompoundAudioExtensions() {
        assertEquals("歌.mp3", FilenamePolicy.outputName("歌.kgm.flac", OutputFormat.MP3))
        assertEquals("歌.flac", FilenamePolicy.outputName("歌.KGMA", OutputFormat.FLAC))
        assertEquals("歌.m4a", FilenamePolicy.outputName("歌.mp3", OutputFormat.M4A))
    }
    @Test fun rejectsTemporaryAndUnsupportedEncryptedDownloads() {
        assertFalse(FilenamePolicy.musicName("歌.kgg"))
        assertFalse(FilenamePolicy.musicName("歌.kgg.flac"))
        assertFalse(FilenamePolicy.musicName("歌.mp3.part"))
        assertFalse(FilenamePolicy.musicName(".kgx-recording.mp3"))
        assertTrue(FilenamePolicy.musicName("歌.kgm.flac"))
    }
    @Test fun producesSingleSafeFatComponentAndBoundsUnicodeLength() {
        assertEquals("evil.mp3", FilenamePolicy.outputName("../../evil.kgm", OutputFormat.MP3))
        assertEquals("_CON.mp3", FilenamePolicy.outputName("CON.kgm", OutputFormat.MP3))
        assertEquals("song______.mp3", FilenamePolicy.outputName("song<>:\"?*.kgm", OutputFormat.MP3))
        val longName = FilenamePolicy.outputName("🎵".repeat(200) + ".kgm", OutputFormat.MP3)
        assertTrue(longName.toByteArray().size <= 184)
        assertFalse(longName.contains('\uFFFD'))
    }
}
