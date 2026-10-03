package com.kgm2mp3_usb.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in network check while the fixed public repository still publishes v1.0.0. */
@RunWith(AndroidJUnit4::class)
class UpdateLiveCheckTest {
    @Test fun publicOldReleaseDoesNotPromptAnUpdateForTheNewApp() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live GitHub verification requires runLiveUpdateCheck=true.",
            arguments.getString("runLiveUpdateCheck").equals("true", ignoreCase = true))

        assertTrue("This live check requires an installed app newer than v1.0.0.",
            releaseVersionIsNewer(BuildConfig.VERSION_NAME, "1.0.0"))
        val manager = UpdateManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val delivered = AtomicReference<Result<UpdateManager.Release?>?>()
        val callback = CountDownLatch(1)
        try {
            // Exercise the production API and fixed source. No mocked transport or injected URL.
            manager.check { result ->
                delivered.set(result)
                callback.countDown()
            }
            assertTrue("The real GitHub update callback did not complete within 45 seconds.",
                callback.await(45, TimeUnit.SECONDS))
            val result = checkNotNull(delivered.get()) { "The update callback returned no result." }
            result.exceptionOrNull()?.let { failure ->
                throw AssertionError("Checking the fixed public GitHub repository failed: ${failure.message}", failure)
            }
            assertNull("The public v1.0.0 release must not be offered as an update for ${BuildConfig.VERSION_NAME}.",
                result.getOrThrow())
        } finally {
            manager.close()
        }
    }
}
