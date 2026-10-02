package com.kgx2mp3.core

enum class AudioFormat(val extension: String) {
    MP3("mp3"), FLAC("flac"), WAV("wav"), OGG("ogg"), M4A("m4a"), AAC("aac")
}

/** Identifies supported audio containers. This is not a full audio validator. */
object AudioSniffer {
    fun detect(bytes: ByteArray): AudioFormat? {
        if (matches(bytes, "fLaC") && bytes.size >= 8) {
            // The first FLAC metadata block must be 34-byte STREAMINFO.
            if ((unsigned(bytes[4]) and 0x7F) == 0 &&
                unsigned(bytes[5]) == 0 && unsigned(bytes[6]) == 0 && unsigned(bytes[7]) == 34
            ) return AudioFormat.FLAC
        }
        if (matches(bytes, "RIFF") && matches(bytes, "WAVE", 8)) return AudioFormat.WAV
        if (matches(bytes, "OggS") && bytes.size >= 27 && bytes[4] == 0.toByte()) return AudioFormat.OGG
        if (isIsoAudioContainer(bytes)) return AudioFormat.M4A
        if (isAdts(bytes)) return AudioFormat.AAC
        if (isId3(bytes) || isMp3Frame(bytes)) return AudioFormat.MP3
        return null
    }

    private fun isId3(bytes: ByteArray): Boolean {
        if (!matches(bytes, "ID3") || bytes.size < 10) return false
        val version = unsigned(bytes[3])
        if (version !in 2..4 || unsigned(bytes[4]) == 255) return false
        val validFlags = when (version) { 2 -> 0xC0; 3 -> 0xE0; else -> 0xF0 }
        if (unsigned(bytes[5]) and validFlags.inv() != 0) return false
        return (6..9).all { unsigned(bytes[it]) and 0x80 == 0 }
    }

    private fun isMp3Frame(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || unsigned(bytes[0]) != 0xFF) return false
        val second = unsigned(bytes[1])
        if (second and 0xE0 != 0xE0) return false
        val version = (second shr 3) and 3
        val layer = (second shr 1) and 3
        val bitrate = (unsigned(bytes[2]) shr 4) and 15
        val sampleRate = (unsigned(bytes[2]) shr 2) and 3
        // MP3 is MPEG Audio Layer III. Reserved fields and free bitrate are
        // deliberately rejected to avoid classifying arbitrary FF bytes.
        return version != 1 && layer == 1 && bitrate in 1..14 && sampleRate != 3
    }

    private fun isAdts(bytes: ByteArray): Boolean {
        if (bytes.size < 7 || unsigned(bytes[0]) != 0xFF) return false
        val second = unsigned(bytes[1])
        // ADTS syncword + layer 00; accept MPEG-2/4 and CRC/no CRC.
        if (second and 0xF6 != 0xF0) return false
        val frequency = (unsigned(bytes[2]) shr 2) and 15
        if (frequency > 12) return false
        val frameLength = ((unsigned(bytes[3]) and 3) shl 11) or
            (unsigned(bytes[4]) shl 3) or (unsigned(bytes[5]) shr 5)
        val headerLength = if (second and 1 == 0) 9 else 7
        return frameLength >= headerLength
    }

    private fun isIsoAudioContainer(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || !matches(bytes, "ftyp", 4)) return false
        val boxSize = (0..3).fold(0L) { n, i -> (n shl 8) or unsigned(bytes[i]).toLong() }
        if (boxSize !in 16L..1_048_576L) return false
        val brand = String(bytes, 8, 4, Charsets.US_ASCII)
        return brand in setOf("M4A ", "M4B ", "M4P ", "isom", "iso2", "mp41", "mp42", "qt  ")
    }

    private fun matches(bytes: ByteArray, value: String, offset: Int = 0): Boolean =
        bytes.size >= offset + value.length && value.indices.all { unsigned(bytes[offset + it]) == value[it].code }

    private fun unsigned(byte: Byte): Int = byte.toInt() and 0xFF
}
