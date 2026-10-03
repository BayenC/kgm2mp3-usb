package com.kgm2mp3_usb.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise Android's actual signed-APK parser, in addition to the JVM policy tests. */
@RunWith(AndroidJUnit4::class)
class UpdateArchiveTest {
    @Test fun installedSignedArchiveDeclaresNativeLibrariesCompatibleWithTheDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archiveAbis = nativeAbisInArchive(File(context.applicationInfo.sourceDir))
        assertTrue(archiveAbis.isNotEmpty())
        verifyNativeAbiCompatibility(archiveAbis, Build.SUPPORTED_ABIS.toList())
    }

    @Test fun signedCurrentPackageCannotBeInstalledAsAnUpdate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = UpdateManager(context)
        val installed = File(context.applicationInfo.sourceDir)
        try {
            val failure = assertThrows(IllegalArgumentException::class.java) { manager.installationIntent(installed) }
            assertTrue(failure.message.orEmpty().contains("较旧"))
        } finally { manager.close() }
    }

    @Test fun downloadedPartFileStillUsesActualSignedPackageMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = UpdateManager(context)
        val working = File(context.cacheDir, "updates").apply { mkdirs() }
        val temporary = File(working, "archive-verification-test.part")
        try {
            File(context.applicationInfo.sourceDir).copyTo(temporary, overwrite = true)
            val failure = assertThrows(IllegalArgumentException::class.java) { manager.installationIntent(temporary) }
            assertTrue(failure.message.orEmpty().contains("较旧"))
        } finally { temporary.delete(); manager.close() }
    }

    @Test fun anotherRealSignedPackageIsRejected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = UpdateManager(instrumentation.targetContext)
        val otherSignedPackage = File(instrumentation.context.applicationInfo.sourceDir)
        try {
            val failure = assertThrows(IllegalArgumentException::class.java) { manager.installationIntent(otherSignedPackage) }
            assertTrue(failure.message.orEmpty().contains("不属于"))
        } finally { manager.close() }
    }

    @Test fun unparseableArchiveIsRejectedBeforeAnInstallerIntentExists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = UpdateManager(context)
        val broken = File(context.cacheDir, "invalid-update-test.apk")
        try {
            broken.writeText("This is not an Android package.")
            assertThrows(IllegalStateException::class.java) { manager.installationIntent(broken) }
        } finally { broken.delete(); manager.close() }
    }
}
