package dev.ipf.whitenoise.android.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.parseMediaImetaTag
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

    @Test
    fun packagedMdkRejectsOtherUnsafeProfileImageAuthoritiesBeforeDial() =
        withMarmot { marmot ->
            listOf(
                "http://profiles.example/avatar.png",
                "https://user@profiles.example/avatar.png",
                "https://profiles.example:8443/avatar.png",
                "https://profiles.example/avatar.png#fragment",
            ).forEach { url ->
                val failure =
                    try {
                        withTimeout(5_000L) { marmot.downloadProfileImage(url, 1_024uL) }
                        null
                    } catch (error: Throwable) {
                        error
                    }

                assertTrue(
                    "Expected packaged MDK to reject $url as an invalid media reference; got $failure",
                    failure is MarmotKitException.InvalidMediaReference,
                )
            }
        }

    @Test
    fun packagedMdkRejectsPeerSuppliedLoopbackMediaLocator() {
        val hash = "01".repeat(32)
        val failure =
            try {
                parseMediaImetaTag(
                    MessageTagFfi(
                        values =
                            listOf(
                                "imeta",
                                "v encrypted-media-v1",
                                "locator blossom-v1 https://127.0.0.1/$hash.bin",
                                "ciphertext_sha256 $hash",
                                "plaintext_sha256 ${"02".repeat(32)}",
                                "nonce ${"03".repeat(12)}",
                                "m image/png",
                                "filename hostile.png",
                            ),
                    ),
                    1uL,
                )
                null
            } catch (error: Throwable) {
                error
            }

        assertTrue(
            "Expected packaged MDK to reject a peer-supplied loopback media locator; got $failure",
            failure is MarmotKitException.InvalidMediaReference,
        )
    }

    private fun withMarmot(block: suspend (Marmot) -> Unit) =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "profile-image-safety-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                Marmot(root.absolutePath, emptyList()).use { marmot -> block(marmot) }
            } finally {
                root.deleteRecursively()
            }
        }
}
