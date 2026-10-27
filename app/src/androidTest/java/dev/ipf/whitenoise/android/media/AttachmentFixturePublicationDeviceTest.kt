package dev.ipf.whitenoise.android.media

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/** Exercises the fixture teardown barrier with a small own-send that is already available from L1. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
class AttachmentFixturePublicationDeviceTest {
    /** A memory hit must not let the fixture stop its state before the authenticated encrypted copy is readable. */
    @Test
    fun smallMemoryHitStillWaitsForVerifiedDiskPublication() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = Files.createTempDirectory(context.cacheDir.toPath(), "fixture-publication").toFile()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cache =
                DiskByteCache(
                    root,
                    maxBytes = 1024 * 1024L,
                    keyProvider = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 17 }, "AES") },
                    afterPutEpochCaptured = {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS)) { "publication release timed out" }
                    },
                )
            val state = withContext(Dispatchers.Main.immediate) { fixtureState(context) }
            WhiteNoiseAppState::class.java
                .getDeclaredField("diskMediaCache")
                .apply { isAccessible = true }
                .set(state, cache)
            val request = AttachmentTransferRequest("fixture", "b2".repeat(32), "c3".repeat(32), 0)
            val expected = ByteArray(19_832) { (it % 127).toByte() }
            try {
                withContext(Dispatchers.Main.immediate) {
                    state.cacheUploadedAttachment(request.cacheKey(), expected, "d4".repeat(32))
                    assertArrayEquals(expected, state.cachedMediaPlaintext(request.cacheKey()))
                }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                assertTrue(state.hasHostCachedAttachmentAfterHydration(request))
                val durable =
                    async {
                        awaitFixtureHostPublication(state, request, expected)
                    }
                assertNull(
                    "L1 availability cannot complete the teardown barrier",
                    withTimeoutOrNull(500L) { durable.await() },
                )
                release.countDown()
                assertTrue(withTimeout(10_000L) { durable.await() })
                assertArrayEquals(expected, withContext(Dispatchers.IO) { cache.get(request.cacheKey()) })
            } finally {
                release.countDown()
                state.outgoingAttachmentCachePublications.await(request.cacheKey())
                state.mutationsScope.cancel()
                root.deleteRecursively()
            }
        }

    /** Creates no account or native runtime and confines preferences to the disposable instrumentation fixture. */
    private fun fixtureState(context: Context): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyPublicationDrafts),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "",
            preferences = context.getSharedPreferences("fixture-publication", Context.MODE_PRIVATE),
            marmotRuntimeFactory = { error("Publication proof must not open a native runtime") },
        )
}

/** The publication probe never reads or persists user drafts. */
private object EmptyPublicationDrafts : DraftPersistence {
    /** The synthetic fixture has no persisted draft. */
    override fun read(): Map<String, String> = emptyMap()

    /** Only the disposable fixture can call this no-op persistence seam. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
