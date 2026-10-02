package com.kgx2mp3.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdatePolicyTest {
    private val digest = "0123456789abcdef".repeat(4)

    @Test fun onlyExactHttpsGithubHostsAreAccepted() {
        assertEquals("github.com", validatedGithubUpdateUrl("https://github.com/owner/repo/releases/download/v2/app.apk").host)
        assertEquals("release-assets.githubusercontent.com", validatedGithubUpdateUrl("https://release-assets.githubusercontent.com/asset").host)
        listOf("http://github.com/asset", "https://github.com.attacker.example/asset",
            "https://attacker.example/asset", "https://user@github.com/asset", "https://github.com:8080/asset")
            .forEach { address -> assertThrows(IllegalArgumentException::class.java) { validatedGithubUpdateUrl(address) } }
    }

    @Test fun checksumRequiresExactFilenameOrStandaloneDigest() {
        assertEquals(digest, checksumForApk("$digest  app.apk\n", "app.apk"))
        assertEquals(digest, checksumForApk("${digest.uppercase()} *app.apk", "app.apk"))
        assertEquals(digest, checksumForApk(digest, "app.apk"))
        assertThrows(IllegalStateException::class.java) { checksumForApk("$digest other.apk", "app.apk") }
        assertThrows(IllegalStateException::class.java) { checksumForApk("$digest ../app.apk", "app.apk") }
        assertThrows(IllegalStateException::class.java) { checksumForApk("bad-hash app.apk", "app.apk") }
    }

    @Test fun ambiguousChecksumFilesAreRejected() {
        assertThrows(IllegalStateException::class.java) { checksumForApk("$digest\n$digest app.apk", "app.apk") }
    }

    @Test fun updateMustBelongToInstalledPackage() {
        val installed = UpdatePackageIdentity("com.kgx2mp3.app", 1, setOf(digest))
        val otherApp = installed.copy(packageName = "attacker.app", versionCode = 2)
        assertThrows(IllegalArgumentException::class.java) { verifyUpdateIdentity(installed, otherApp) }
    }

    @Test fun onlyStrictlyNewerSignedVersionIsAccepted() {
        val installed = UpdatePackageIdentity("com.kgx2mp3.app", 2, setOf(digest))
        verifyUpdateIdentity(installed, installed.copy(versionCode = 3))
        listOf(2L, 1L, -1L).forEach { version ->
            assertThrows(IllegalArgumentException::class.java) { verifyUpdateIdentity(installed, installed.copy(versionCode = version)) }
        }
    }

    @Test fun certificateMismatchOrExtraSignerIsRejected() {
        val installed = UpdatePackageIdentity("com.kgx2mp3.app", 1, setOf(digest))
        listOf(setOf("different-certificate"), setOf(digest, "extra-signer"), emptySet()).forEach { certs ->
            assertThrows(IllegalArgumentException::class.java) {
                verifyUpdateIdentity(installed, installed.copy(versionCode = 2, certificates = certs))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyUpdateIdentity(installed.copy(certificates = emptySet()), installed.copy(versionCode = 2, certificates = emptySet()))
        }
    }
}
