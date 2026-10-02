package com.kgx2mp3.app

enum class OutputFormat(val extension: String, val displayName: String) {
    MP3("mp3", "MP3"), FLAC("flac", "FLAC"), WAV("wav", "WAV"), M4A("m4a", "M4A")
}

data class SongRef(
    val id: String,
    val name: String,
    val sourceUri: String? = null,
    val path: String? = null,
    val size: Long = 0,
    val lastModified: Long = 0,
    val encrypted: Boolean = false,
)

data class StorageTarget(
    val id: String,
    val label: String,
    val rootPath: String? = null,
    val treeUri: String? = null,
)

data class ScanResult(val songs: List<SongRef>, val error: String? = null)
data class TransferFailure(val song: SongRef, val reason: String)
data class TransferSnapshot(
    val running: Boolean = false,
    val stage: String = "",
    val position: Int = 0,
    val total: Int = 0,
    val successes: Int = 0,
    val failures: List<TransferFailure> = emptyList(),
    val currentName: String = "",
    val progress: Int = 0,
)
