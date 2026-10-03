package com.kgm2mp3_usb.app

import java.io.File
import java.math.BigInteger
import java.util.zip.ZipFile

/** One release source for every installation; preference files cannot change it. */
internal const val UPDATE_REPOSITORY = "BayenC/kgm2mp3-usb"
internal const val UPDATE_RELEASE_URL = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest"

/** Compare numeric components and SemVer prereleases, never tags as text. */
internal fun releaseVersionIsNewer(tag: String, installedVersion: String): Boolean {
    val incoming = NumericReleaseVersion.parse(tag)
        ?: error("更新版本号无法识别，请稍后再试。")
    val installed = NumericReleaseVersion.parse(installedVersion)
        ?: error("当前版本号无法识别，请从官方发布页下载新版。")
    return incoming > installed
}

private data class NumericReleaseVersion(
    val numbers: List<BigInteger>, val prerelease: List<String>
) : Comparable<NumericReleaseVersion> {
    override fun compareTo(other: NumericReleaseVersion): Int {
        numbers.indices.forEach { index ->
            val comparison = numbers[index].compareTo(other.numbers[index])
            if (comparison != 0) return comparison
        }
        if (prerelease.isEmpty()) return if (other.prerelease.isEmpty()) 0 else 1
        if (other.prerelease.isEmpty()) return -1
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
            val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val VERSION = Regex("^[vV]?(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?(?:\\.(0|[1-9][0-9]*))?(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")
        fun parse(text: String): NumericReleaseVersion? {
            if (text.length > 128) return null
            val match = VERSION.matchEntire(text.trim()) ?: return null
            val numbers = (1..3).map { match.groupValues[it].ifEmpty { "0" }.toBigInteger() }
            val prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            if (prerelease.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return NumericReleaseVersion(numbers, prerelease)
        }
    }
}

internal data class UpdateAsset(val name: String, val url: String)
internal data class UpdateAssetPair(val apk: UpdateAsset, val checksum: UpdateAsset)

/** Names identify candidate ABIs; the downloaded APK's actual lib entries are checked again. */
internal fun selectUpdateAssets(assets: List<UpdateAsset>, supportedAbis: List<String>): UpdateAssetPair {
    require(supportedAbis.isNotEmpty()) { "无法识别这部手机的处理器，请从官方发布页下载适用版本。" }
    val candidates = assets.filter { asset ->
        asset.name.endsWith(".apk", ignoreCase = true) && !TEST_ASSET.containsMatchIn(asset.name.lowercase())
    }.mapNotNull { asset ->
        val name = asset.name.lowercase()
        val abis = ABI_TOKEN.findAll(name).map { it.value }.toSet()
        val rank = when {
            abis.isNotEmpty() -> supportedAbis.indexOfFirst { it in abis }.takeIf { it >= 0 }
            UNIVERSAL_TOKEN.containsMatchIn(name) -> supportedAbis.size
            else -> null
        }
        rank?.let { asset to it }
    }.sortedWith(compareBy<Pair<UpdateAsset, Int>> { it.second }.thenBy { it.first.name })
    require(candidates.isNotEmpty()) { "这个版本没有适用于这部手机的正式安装包。请稍后再试。" }
    candidates.forEach { (apk, _) ->
        val checksums = assets.filter { it.name == "${apk.name}.sha256" }
        require(checksums.size <= 1 && assets.count { it.name == apk.name } == 1) {
            "更新发布文件重复，请稍后再试。"
        }
        if (checksums.size == 1) return UpdateAssetPair(apk, checksums.single())
    }
    error("这个版本缺少对应的 SHA-256 校验文件，暂时不能安装。")
}

private val ABI_TOKEN = Regex("(?<![a-z0-9])(arm64-v8a|armeabi-v7a|armeabi|x86_64|x86)(?![a-z0-9])")
private val UNIVERSAL_TOKEN = Regex("(^|[._-])universal([._-]|$)")
private val TEST_ASSET = Regex("(^|[._-])(debug|test|tests|testing|androidtest|instrumentation|emulator|unsigned)([._-]|$)")

/** Archive declarations must match the current device, even if an asset is mislabeled. */
internal fun nativeAbisInArchive(file: File): Set<String> = ZipFile(file).use { zip ->
    val library = Regex("lib/([^/]+)/[^/]+\\.so")
    zip.entries().asSequence().filterNot { it.isDirectory }.mapNotNull {
        library.matchEntire(it.name)?.groupValues?.get(1)
    }.toSet()
}

internal fun verifyNativeAbiCompatibility(archiveAbis: Set<String>, supportedAbis: List<String>) {
    require(archiveAbis.isNotEmpty() && supportedAbis.any { it in archiveAbis }) {
        "更新安装包不支持这部手机的处理器，已停止安装。"
    }
}
