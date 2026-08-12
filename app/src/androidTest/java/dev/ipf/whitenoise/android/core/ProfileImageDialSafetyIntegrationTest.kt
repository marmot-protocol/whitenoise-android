package dev.ipf.whitenoise.android.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileImageDialSafetyIntegrationTest {
    @Test
    fun packagedMdkRejectsLoopbackProfileImageBeforeDial() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "profile-image-safety-${UUID.randomUUID()}").apply { mkdirs() }
            val marmot = Marmot(root.absolutePath, emptyList())

            val failure =
                try {
                    withTimeout(5_000L) {
                        marmot.downloadProfileImage("https://127.0.0.1/avatar.png", 1_024uL)
                    }
                    null
                } catch (error: Throwable) {
                    error
                } finally {
                    marmot.close()
                    root.deleteRecursively()
                }

            assertTrue(
                "Expected packaged MDK to reject a loopback profile image as an invalid media reference; got $failure",
                failure is MarmotKitException.InvalidMediaReference,
            )
        }
}
