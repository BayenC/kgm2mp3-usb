package com.kgm2mp3_usb.app

import java.util.Locale

internal object FilenamePolicy {
    private val encrypted = Regex("\\.(kgm|kgma)(\\.(flac|mp3|ogg|wav|m4a|aac))?$", RegexOption.IGNORE_CASE)
    private val normal = Regex("\\.(mp3|flac|wav|m4a|aac|ogg|opus|wma|ape|aiff|aif)$", RegexOption.IGNORE_CASE)
    private val temporary = Regex("(^\\.|\\.(part|tmp|temp|download|crdownload|bak)$|\\.kgg($|\\.))", RegexOption.IGNORE_CASE)
    fun encryptedName(name: String) = encrypted.containsMatchIn(name)
    fun musicName(name: String) = !temporary.containsMatchIn(name) &&
        (encryptedName(name) || normal.containsMatchIn(name))

    /** Conservative FAT/exFAT name: one component, no reserved DOS names, bounded UTF-8 length. */
    fun outputName(inputName: String, format: OutputFormat): String {
        val leaf = inputName.substringAfterLast('/').substringAfterLast('\\')
        var stem = encrypted.replace(leaf, "")
        if (stem == leaf) stem = leaf.substringBeforeLast('.', leaf)
        stem = stem.replace(Regex("[\\x00-\\x1F<>:\"/\\\\|?*]"), "_").trim().trimEnd('.', ' ')
        if (stem.isBlank() || stem == "." || stem == "..") stem = "歌曲"
        val reserved = stem.substringBefore('.').uppercase(Locale.ROOT)
        if (reserved in setOf("CON", "PRN", "AUX", "NUL") ||
            Regex("(COM|LPT)[1-9]").matches(reserved)) stem = "_$stem"
        // Leave room for extension and transaction names on filesystems with 255-byte limits.
        while (stem.toByteArray(Charsets.UTF_8).size > 180) {
            stem = stem.dropLast(if (stem.last().isLowSurrogate() && stem.length > 1) 2 else 1)
        }
        return "$stem.${format.extension}"
    }
}
