package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentLocalAssetFfi
import dev.ipf.marmotkit.AttachmentLocalBytesFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.DiskByteCacheKeyProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/** Shipping cache waits and native-first reads under a deliberately delayed outgoing publication. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class OutgoingAttachmentPublicationIntegrationTest {
    /** Removing the shipping host-cache publication wait makes this probe return a premature miss. */
    @Test
    fun productionHostAvailabilityWaitsForOutgoingPublication() {
        assertOutgoingPublicationRead(nativeRetained = false, hostOnly = true)
    }

    /** Removing the shipping plaintext publication wait would admit unwanted acquisition. */
    @Test
    fun productionPlaintextWaitsForOutgoingPublicationWhenNativeIsAbsent() {
        assertOutgoingPublicationRead(nativeRetained = false, hostOnly = false)
    }

    /** A retained native source opens before the deliberately blocked encrypted host copy completes. */
    @Test
    fun productionPlaintextBypassesPendingHostCopyWithNativeRetention() {
        assertOutgoingPublicationRead(nativeRetained = true, hostOnly = false)
    }

    /** Uses the actual oversized-L1 handoff and AppState resolver, with every network method forbidden. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("LongMethod") // Both pending-write and native-first behavior share the same shipping boundary.
    private fun assertOutgoingPublicationRead(nativeRetained: Boolean, hostOnly: Boolean) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val directory = Files.createTempDirectory("production-outgoing-publication").toFile()
            val enteredWrite = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val state = mediaSendReconciliationAppState()
            val expected = ByteArray(8 * 1024 * 1024 + 1) { (it % 127).toByte() }
            val cache =
                DiskByteCache(
                    directory,
                    maxBytes = 64L * 1024 * 1024,
                    keyProvider = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 7 }, "AES") },
                    afterPutEpochCaptured = {
                        enteredWrite.countDown()
                        check(releaseWrite.await(10, TimeUnit.SECONDS)) { "publication test release timed out" }
                    },
                )
            WhiteNoiseAppState::class.java
                .getDeclaredField("diskMediaCache")
                .apply { isAccessible = true }
                .set(state, cache)
            val engine =
                Proxy.newProxyInstance(
                    MarmotInterface::class.java.classLoader,
                    arrayOf(MarmotInterface::class.java),
                ) { proxy, method, args ->
                    when (val name = method.name.substringBefore('-')) {
                        "toString" -> "outgoing-publication-boundary"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                        "attachmentLocalAssets" ->
                            listOf(
                                AttachmentLocalAssetFfi(
                                    if (nativeRetained) "retained" else null,
                                    expected.size.toULong(),
                                ),
                            )
                        "readAttachmentAsset" -> {
                            val offset = (args!![2] as Long).toInt()
                            val limit = args[3] as Int
                            AttachmentLocalBytesFfi(
                                true,
                                expected.copyOfRange(offset, minOf(expected.size, offset + limit)),
                            )
                        }
                        else -> error("Outgoing publication must never acquire network data: $name")
                    }
                } as MarmotInterface
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime("test", engine))
            val request = AttachmentTransferRequest(ACCOUNT_REF, GROUP_ID, CONFIRMED_MESSAGE_ID, 0, "d4".repeat(32))
            try {
                state.cacheUploadedAttachment(request.cacheKey(), expected, "a".repeat(64))
                assertNull(state.cachedMediaPlaintext(request.cacheKey()))
                assertTrue(withContext(Dispatchers.IO) { enteredWrite.await(5, TimeUnit.SECONDS) })
                assertTrue(state.outgoingAttachmentCachePublications.isPending(request.cacheKey()))
                val startedRead = CompletableDeferred<Unit>()
                val read =
                    async {
                        startedRead.complete(Unit)
                        if (hostOnly) {
                            assertTrue(state.hasHostCachedAttachmentAfterHydration(request))
                        } else {
                            val source =
                                state.downloadAttachmentPlaintextSource(
                                    request,
                                    MediaDownloadIntegrationFixture.reference(0),
                                    persistInteractiveIntent = false,
                                )
                            source.use {
                                assertTrue(source is AttachmentPlaintext.Lease)
                                assertArrayEquals(expected, (source as AttachmentPlaintext.Lease).file.readBytes())
                            }
                        }
                    }
                startedRead.await()
                if (nativeRetained) {
                    read.await()
                    assertTrue(
                        "native read finishes while host encryption remains blocked",
                        state.outgoingAttachmentCachePublications.isPending(request.cacheKey()),
                    )
                } else {
                    assertNull(
                        "matching publication must precede host reads",
                        withContext(Dispatchers.Default) {
                            withTimeoutOrNull(500) {
                                read.await()
                                true
                            }
                        },
                    )
                }
                releaseWrite.countDown()
                read.await()
                state.outgoingAttachmentCachePublications.await(request.cacheKey())
            } finally {
                releaseWrite.countDown()
                state.outgoingAttachmentCachePublications.await(request.cacheKey())
                state.mutationsScope.cancel()
                directory.deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val GROUP_ID = "b2".repeat(32)
        val CONFIRMED_MESSAGE_ID = "c3".repeat(32)
    }
}
