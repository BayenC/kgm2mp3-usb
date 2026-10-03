package com.kgm2mp3_usb.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateReleasePolicyTest {
    @get:Rule val files = TemporaryFolder()

    private fun asset(name: String) = UpdateAsset(name, "https://github.com/BayenC/kgm2mp3-usb/releases/download/v1.2.0/$name")
    private fun signedAsset(name: String) = listOf(asset(name), asset("$name.sha256"))

    @Test fun olderAndEqualPublicReleasesDoNotPromptAnUpdate() {
        assertFalse(releaseVersionIsNewer("v1.0.0", "1.1.0"))
        assertFalse(releaseVersionIsNewer("v1.1.0", "1.1.0"))
        assertFalse(releaseVersionIsNewer("V1.1", "1.1.0"))
        assertFalse(releaseVersionIsNewer("1.1.0+different-build", "1.1.0+installed-build"))
    }

    @Test fun versionNumbersAreComparedNumericallyWithoutOverflow() {
        assertTrue(releaseVersionIsNewer("v1.10.0", "1.9.99"))
        assertTrue(releaseVersionIsNewer("v2.0.0", "1.99.99"))
        assertFalse(releaseVersionIsNewer("v1.2.99", "1.10.0"))
        assertTrue(releaseVersionIsNewer("v999999999999999999999999999.0.0", "1.1.0"))
    }

    @Test fun prereleaseUsesSemanticVersionOrdering() {
        assertTrue(releaseVersionIsNewer("1.2.0", "1.2.0-rc.10"))
        assertFalse(releaseVersionIsNewer("1.2.0-rc.10", "1.2.0"))
        assertTrue(releaseVersionIsNewer("1.2.0-rc.10", "1.2.0-rc.2"))
        assertTrue(releaseVersionIsNewer("1.2.0-beta", "1.2.0-alpha"))
        assertTrue(releaseVersionIsNewer("1.2.0-alpha.1", "1.2.0-alpha"))
        assertTrue(releaseVersionIsNewer("1.2.0-alpha.-1", "1.2.0-alpha.1"))
    }

    @Test fun unrecognizedVersionTagsCannotBeCalledNewer() {
        listOf("latest", "release-1.2.0", "1.02.0", "1.2.0.4", "1.2.0-rc.01", "1.2.0+").forEach { tag ->
            assertThrows(IllegalStateException::class.java) { releaseVersionIsNewer(tag, "1.1.0") }
        }
        assertThrows(IllegalStateException::class.java) { releaseVersionIsNewer("1.2.0", "unrecognized") }
    }

    @Test fun fixedSourceIsThePublishedRepository() {
        assertEquals("BayenC/kgm2mp3-usb", UPDATE_REPOSITORY)
        assertEquals("https://api.github.com/repos/BayenC/kgm2mp3-usb/releases/latest", UPDATE_RELEASE_URL)
    }

    @Test fun arm64PhoneSelectsItsApkAndExactChecksumOverUniversalOrX86() {
        val name = "music-transfer-1.2.0-arm64-v8a.apk"
        val assets = signedAsset("music-transfer-1.2.0-universal.apk") + signedAsset("music-transfer-1.2.0-x86_64.apk") + signedAsset(name)
        val selected = selectUpdateAssets(assets, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(name, selected.apk.name)
        assertEquals("$name.sha256", selected.checksum.name)
    }

    @Test fun x86EmulatorCannotBeOfferedArm64OnlyRelease() {
        assertThrows(IllegalArgumentException::class.java) {
            selectUpdateAssets(signedAsset("music-transfer-1.2.0-arm64-v8a.apk"), listOf("x86_64", "x86"))
        }
    }

    @Test fun x86_64IsNotConfusedWithX86AndDevicePreferenceWins() {
        val x64 = "music-transfer-1.2.0-x86_64.apk"
        val x86 = "music-transfer-1.2.0-x86.apk"
        assertEquals(x64, selectUpdateAssets(signedAsset(x86) + signedAsset(x64), listOf("x86_64", "x86")).apk.name)
        assertThrows(IllegalArgumentException::class.java) { selectUpdateAssets(signedAsset(x64), listOf("x86")) }
    }

    @Test fun universalCanBeAFallbackButUnidentifiedArchitectureCannot() {
        val universal = "music-transfer-1.2.0-universal.apk"
        assertEquals(universal, selectUpdateAssets(signedAsset("music-transfer-1.2.0-arm64-v8a.apk") + signedAsset(universal), listOf("x86_64")).apk.name)
        assertThrows(IllegalArgumentException::class.java) { selectUpdateAssets(signedAsset("music-transfer-1.2.0.apk"), listOf("arm64-v8a")) }
    }

    @Test fun debugAndTestArtifactsAreNeverOfferedForInstallation() {
        val forbidden = listOf("debug", "test", "androidTest", "instrumentation", "emulator", "unsigned")
            .flatMap { kind -> signedAsset("music-transfer-1.2.0-$kind-arm64-v8a.apk") }
        assertThrows(IllegalArgumentException::class.java) { selectUpdateAssets(forbidden, listOf("arm64-v8a")) }
        val release = "music-transfer-1.2.0-arm64-v8a.apk"
        assertEquals(release, selectUpdateAssets(forbidden + signedAsset(release), listOf("arm64-v8a")).apk.name)
    }

    @Test fun aChecksumForAnotherFileCannotAuthorizeAnApk() {
        val assets = listOf(asset("music-transfer-1.2.0-arm64-v8a.apk"), asset("other.apk.sha256"))
        assertThrows(IllegalStateException::class.java) { selectUpdateAssets(assets, listOf("arm64-v8a")) }
    }

    @Test fun universalWithChecksumCanReplaceMissingDeviceChecksum() {
        val universal = "music-transfer-1.2.0-universal.apk"
        val assets = listOf(asset("music-transfer-1.2.0-arm64-v8a.apk")) + signedAsset(universal)
        assertEquals(universal, selectUpdateAssets(assets, listOf("arm64-v8a")).apk.name)
    }

    @Test fun duplicateAssetNamesAreRejectedInsteadOfChoosingAnArbitraryFile() {
        val assets = signedAsset("music-transfer-1.2.0-arm64-v8a.apk")
        assertThrows(IllegalArgumentException::class.java) {
            selectUpdateAssets(assets + assets.first().copy(url = "https://github.com/other.apk"), listOf("arm64-v8a"))
        }
        assertThrows(IllegalArgumentException::class.java) { selectUpdateAssets(assets + assets.last(), listOf("arm64-v8a")) }
    }

    @Test fun archiveAbiInspectionIgnoresDirectoriesAssetsAndNestedLibraries() {
        val archive = files.newFile("native-declarations.apk")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("lib/arm64-v8a/libffmpegkit.so", "lib/x86_64/libffmpegkit.so", "assets/lib/x86/libfake.so",
                "lib/armeabi-v7a/subfolder/libfake.so", "lib/arm64-v8a/readme.txt", "lib/armeabi/").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.closeEntry()
            }
        }
        assertEquals(setOf("arm64-v8a", "x86_64"), nativeAbisInArchive(archive))
    }

    @Test fun downloadedArchiveCannotClaimCompatibilityOnlyThroughItsFilename() {
        verifyNativeAbiCompatibility(setOf("arm64-v8a", "x86_64"), listOf("x86_64"))
        assertThrows(IllegalArgumentException::class.java) { verifyNativeAbiCompatibility(setOf("arm64-v8a"), listOf("x86_64", "x86")) }
        assertThrows(IllegalArgumentException::class.java) { verifyNativeAbiCompatibility(emptySet(), listOf("arm64-v8a")) }
    }
}
