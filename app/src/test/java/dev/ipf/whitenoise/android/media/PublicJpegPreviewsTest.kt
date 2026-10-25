package dev.ipf.whitenoise.android.media

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.ValidatedInternetNetworkTracker
import dev.ipf.whitenoise.android.ui.conversation.messages.publicJpegPreviewOwnerCurrent
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicJpegPreviewsTest {
    private val digest = "ab".repeat(32)

    @Test
    fun wifiRequiresValidatedInternetAndRejectsMeteredNetworks() {
        val account = testAccount("alice", "aa".repeat(32))
        val state = emptyAppState(accounts = listOf(account), activeAccountRef = "alice")

        fun field(name: String) = state.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field("hasActiveNetworkSnapshot").setBoolean(state, true)
        field("activeNetworkTypesSnapshot").set(state, setOf(MediaAutoDownloadNetwork.WiFi))
        val tracker = field("validatedInternetNetworks").get(state) as ValidatedInternetNetworkTracker
        assertFalse(state.publicJpegPreviewNetworkAllowed())
        tracker.update(1L, available = true)
        assertTrue(state.publicJpegPreviewNetworkAllowed())
        field("activeNetworkTypesSnapshot").set(
            state,
            setOf(MediaAutoDownloadNetwork.WiFi, MediaAutoDownloadNetwork.Metered),
        )
        assertFalse(state.publicJpegPreviewNetworkAllowed())
        tracker.remove(1L)
        assertFalse(state.publicJpegPreviewNetworkAllowed())
    }

    @Test
    fun onlyDirectHttpsContentAddressedJpegLocatorsAreEligible() {
        listOf("", ".jpg", ".jpeg").forEach { suffix ->
            assertEquals(digest, publicJpegDigest("https://images.example/$digest$suffix"))
        }
        listOf(
            "http://images.example/$digest.jpg",
            "https://reader:secret@images.example/$digest.jpg",
            "https://images.example:8080/$digest.jpg",
            "https://images.example/$digest.png",
            "https://images.example/image.jpg",
            "https://images.example/nested/$digest.jpg",
            "https://images.example/$digest.jpg?track=reader",
            "https://images.example/$digest.jpg#fragment",
            "data:image/jpeg;base64,test",
        ).forEach { assertNull(it, publicJpegDigest(it)) }
    }

    @Test
    fun htmlSvgTruncatedWrongHashAndOversizedBytesNeverReachDecoder() {
        listOf(
            "<html>image</html>".toByteArray(),
            "<svg/>".toByteArray(),
            byteArrayOf(-1, -40, -1),
            ByteArray(PUBLIC_JPEG_MAX_BYTES.toInt() + 1),
        ).forEach { assertFalse(publicJpegPayloadMatches(it, digest)) }
        val multiPicture = byteArrayOf(-1, -40, -1, -30, 0, 6, 77, 80, 70, 0, -1, -38, 0, 2, -1, -39)
        val hash = MessageDigest.getInstance("SHA-256").digest(multiPicture).joinToString("") { "%02x".format(it) }
        assertFalse(publicJpegPayloadMatches(multiPicture, hash))
    }

    @Test
    fun realStaticJpegIsAcceptedButConcatenatedImagesAndTrailingDataAreRejected() {
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 80, output))
        val bytes = output.toByteArray()

        fun hash(value: ByteArray) =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value)
                .joinToString("") { "%02x".format(it) }
        assertTrue(publicJpegPayloadMatches(bytes, hash(bytes)))
        val concatenated = bytes + bytes
        assertFalse(publicJpegPayloadMatches(concatenated, hash(concatenated)))
        val trailing = bytes + byteArrayOf(1, 2, 3)
        assertFalse(publicJpegPayloadMatches(trailing, hash(trailing)))
        assertFalse(publicJpegPayloadMatches(bytes, digest))
    }

    @Test
    fun reusedAccountLabelCannotAdmitThePreviousSigningIdentityOrRuntime() {
        val original = testAccount("same-label", "aa".repeat(32))
        val replacement = testAccount("same-label", "bb".repeat(32))
        val originalState = emptyAppState(accounts = listOf(original), activeAccountRef = "same-label")
        val replacedState = emptyAppState(accounts = listOf(replacement), activeAccountRef = "same-label")
        val generation = originalState.runtimeGeneration
        assertTrue(publicJpegPreviewOwnerCurrent(originalState, "same-label", original.accountIdHex, generation))
        val replacementGeneration = replacedState.runtimeGeneration
        assertFalse(
            publicJpegPreviewOwnerCurrent(replacedState, "same-label", original.accountIdHex, replacementGeneration),
        )
        assertFalse(publicJpegPreviewOwnerCurrent(originalState, "same-label", original.accountIdHex, generation + 1))
        val signedOut =
            emptyAppState(accounts = listOf(original.copy(signedOut = true)), activeAccountRef = "same-label")
        assertFalse(
            publicJpegPreviewOwnerCurrent(signedOut, "same-label", original.accountIdHex, signedOut.runtimeGeneration),
        )
    }

    @Test
    fun rejectsWrongPayloadOnceAndReusesTheBoundedFailureEntry() =
        runBlocking {
            var calls = 0
            val loader = PublicJpegPreviewLoader()
            repeat(3) {
                assertNull(
                    loader.load("alice", "https://images.example/$digest", { true }) { _, maxBytes ->
                        assertEquals(PUBLIC_JPEG_MAX_BYTES.toULong(), maxBytes)
                        calls++
                        "<html>not a JPEG</html>".toByteArray()
                    },
                )
            }
            assertEquals(1, calls)
        }

    @Test
    fun disabledAndRevokedQueuedRequestsNeverCallTransport() =
        runBlocking {
            val loader = PublicJpegPreviewLoader()
            var calls = 0
            assertNull(
                loader.load("alice", "https://images.example/$digest", { false }) { _, _ ->
                    calls++
                    byteArrayOf()
                },
            )
            assertEquals(0, calls)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first =
                async {
                    loader.load("alice", "https://images.example/$digest", { true }) { _, _ ->
                        started.complete(Unit)
                        release.await()
                        byteArrayOf()
                    }
                }
            started.await()
            var allowed = true
            val second =
                async {
                    loader.load("bob", "https://images.example/$digest", { allowed }) { _, _ ->
                        calls++
                        byteArrayOf()
                    }
                }
            allowed = false
            release.complete(Unit)
            first.await()
            assertNull(second.await())
            assertEquals(0, calls)
        }

    @Test
    fun revokedAcquisitionDoesNotPoisonTheFailureCache() =
        runBlocking {
            val loader = PublicJpegPreviewLoader()
            var allowed = true
            var calls = 0
            assertNull(
                loader.load("alice", "https://images.example/$digest", { allowed }) { _, _ ->
                    calls++
                    allowed = false
                    byteArrayOf()
                },
            )
            allowed = true
            assertNull(
                loader.load("alice", "https://images.example/$digest", { allowed }) { _, _ ->
                    calls++
                    byteArrayOf()
                },
            )
            assertEquals(2, calls)
        }

    @Test
    fun removedAccountCannotBeReenabledByALateConsentWrite() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context
                .getSharedPreferences("whitenoise.public_jpeg_previews", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            val preferences = PublicJpegPreviewPreferences(context)
            preferences.retainAccounts(listOf("alice", "bob"))
            assertTrue(preferences.setEnabled("alice", true))
            preferences.retainAccounts(listOf("bob"))
            assertFalse(preferences.setEnabled("alice", true))
            assertFalse(preferences.enabled("alice"))
            assertFalse(PublicJpegPreviewPreferences(context).enabled("alice"))
            assertEquals(emptySet<String>(), preferences.retainAccounts(listOf("bob")))
            preferences.retainAccounts(listOf("alice", "bob"))
            assertFalse(preferences.enabled("alice"))
            assertTrue(preferences.setEnabled("alice", true))
        }

    @Test
    fun consentIsAccountScopedDurableAndDefaultsOff() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context
                .getSharedPreferences("whitenoise.public_jpeg_previews", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            val preferences = PublicJpegPreviewPreferences(context)
            assertFalse(preferences.enabled("alice"))
            assertTrue(preferences.setEnabled("alice", true))
            assertTrue(PublicJpegPreviewPreferences(context).enabled("alice"))
            assertFalse(preferences.enabled("bob"))
            assertTrue(preferences.setEnabled("alice", false))
            assertFalse(PublicJpegPreviewPreferences(context).enabled("alice"))
        }
}
