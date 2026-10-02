package com.kgx2mp3.core

/** Facts and original decoded-PCM digest from the mandatory FLAC STREAMINFO. */
data class FlacStreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val md5Hex: String
) {
    val hasMd5: Boolean get() = md5Hex.length == 32 && md5Hex.any { it != '0' }

    companion object {
        /**
         * Reads the first 42 bytes of a native FLAC stream. Returns null for
         * missing/truncated/malformed STREAMINFO; does not consume any stream.
         * Layout reference: RFC 9639 section 8.2.
         */
        fun parse(prefix: ByteArray): FlacStreamInfo? {
            if (prefix.size < 42 || prefix[0] != 0x66.toByte() || prefix[1] != 0x4C.toByte() ||
                prefix[2] != 0x61.toByte() || prefix[3] != 0x43.toByte() ||
                (unsigned(prefix[4]) and 0x7F) != 0 || unsigned(prefix[5]) != 0 ||
                unsigned(prefix[6]) != 0 || unsigned(prefix[7]) != 34
            ) return null
            val minBlock = (unsigned(prefix[8]) shl 8) or unsigned(prefix[9])
            val maxBlock = (unsigned(prefix[10]) shl 8) or unsigned(prefix[11])
            if (minBlock !in 16..65535 || maxBlock !in minBlock..65535) return null
            val minFrame = read24(prefix, 12)
            val maxFrame = read24(prefix, 15)
            if (minFrame != 0 && maxFrame != 0 && minFrame > maxFrame) return null
            val packed = (18..25).fold(0L) { value, index -> (value shl 8) or unsigned(prefix[index]).toLong() }
            val rate = (packed ushr 44).toInt()
            val channels = ((packed ushr 41) and 7).toInt() + 1
            val depth = ((packed ushr 36) and 31).toInt() + 1
            val samples = packed and 0xFFFFFFFFFL
            if (rate == 0 || depth !in 4..32) return null
            val digest = prefix.copyOfRange(26, 42).joinToString("") { "%02x".format(unsigned(it)) }
            return FlacStreamInfo(rate, channels, depth, samples, digest)
        }

        private fun read24(bytes: ByteArray, offset: Int): Int =
            (unsigned(bytes[offset]) shl 16) or (unsigned(bytes[offset + 1]) shl 8) or unsigned(bytes[offset + 2])

        private fun unsigned(byte: Byte): Int = byte.toInt() and 0xFF
    }
}
