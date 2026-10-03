package com.kgm2mp3_usb.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runtime-built signed archives exercise PackageManager; no signing keys enter the test APK. */
@RunWith(AndroidJUnit4::class)
class SignedUpdateFixtureTest {
    @Test fun higherVersionWithNewIdentityAndSignerProducesInstallerIntent() {
        withFixture("signedUpdatePath") { manager, file ->
            val intent = manager.installationIntent(file)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data?.scheme)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals("${context.packageName}.files", intent.data?.authority)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test fun higherVersionSignedWithOldKeyIsRejected() {
        withFixture("wrongSignerUpdatePath") { manager, file ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                manager.installationIntent(file)
            }
            assertTrue(failure.message.orEmpty().contains("签名"))
        }
    }

    private fun withFixture(argument: String, check: (UpdateManager, File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString(argument)
        assumeNotNull(path)
        val context = instrumentation.targetContext
        val source = File(path!!)
        assertTrue("Push the runtime signed fixture before running this test", source.isFile && source.canRead())
        val local = File(context.cacheDir, "updates/$argument.apk")
        local.parentFile!!.mkdirs()
        source.copyTo(local, overwrite = true)
        val manager = UpdateManager(context)
        try { check(manager, local) }
        finally { local.delete(); manager.close() }
    }
}
