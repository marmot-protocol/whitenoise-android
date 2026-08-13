package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AvatarImageLoaderTest {
    @After
    fun tearDownProfileImageFetcher() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
    }

    @Test
    fun profileImageBytesDelegateHostClassificationAndBoundToMarmot() =
        runBlocking {
            var requestedUrl: String? = null
            var requestedMaxBytes: ULong? = null
            AvatarImageLoader.attachProfileImageFetcher { url, maxBytes ->
                requestedUrl = url
                requestedMaxBytes = maxBytes
                byteArrayOf(1, 2, 3)
            }

            val result = AvatarImageLoader.fetchBytes("https://127.0.0.1/alice.png", 8)

            // Android must not pre-classify a protocol URL. Production MDK
            // rejects this loopback host before dialing it.
            assertEquals("https://127.0.0.1/alice.png", requestedUrl)
            assertEquals(8uL, requestedMaxBytes)
            assertEquals(listOf<Byte>(1, 2, 3), (result as AvatarByteFetchResult.Success).bytes.toList())
        }

    @Test
    fun profileImageBytesRejectAContractViolatingOversizedResult() =
        runBlocking {
            AvatarImageLoader.attachProfileImageFetcher { _, _ -> ByteArray(9) }

            assertEquals(
                AvatarByteFetchResult.Failed,
                AvatarImageLoader.fetchBytes("https://profiles.example/alice.png", 8),
            )
        }

    @Test
    fun unavailableFetcherIsDistinctAndResetRestoresIsolation() =
        runBlocking {
            AvatarImageLoader.resetProfileImageFetcherForTests()
            assertEquals(
                AvatarByteFetchResult.Unavailable,
                AvatarImageLoader.fetchBytes("https://profiles.example/alice.png", 8),
            )

            AvatarImageLoader.attachProfileImageFetcher { _, _ -> byteArrayOf(1) }
            assertEquals(
                listOf<Byte>(1),
                (AvatarImageLoader.fetchBytes("https://profiles.example/alice.png", 8) as AvatarByteFetchResult.Success)
                    .bytes
                    .toList(),
            )
        }

    @Test
    fun unavailableFetcherDoesNotPoisonTheFailureCache() =
        runBlocking {
            val url = "https://profiles.example/late-bootstrap.png"
            AvatarImageLoader.resetProfileImageFetcherForTests()

            assertNull(AvatarImageLoader.load(url))

            var fetchCount = 0
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                fetchCount++
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }
            assertNotNull(AvatarImageLoader.load(url))
            assertEquals(1, fetchCount)
        }

    private companion object {
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }

    @Test
    fun notificationAvatarsUseDedicatedLanesWhenPreWarmsAreSaturated() {
        runBlocking {
            val gate = AvatarFetchGate(regularPermits = 2, notificationPermits = 2)
            val releasePreWarms = CompletableDeferred<Unit>()
            val activePreWarms = AtomicInteger(0)
            val twoPreWarmsStarted = CompletableDeferred<Unit>()

            val preWarms =
                List(2) {
                    async(Dispatchers.Default) {
                        gate.withPreWarmAdmission {
                            if (activePreWarms.incrementAndGet() == 2) {
                                twoPreWarmsStarted.complete(Unit)
                            }
                            releasePreWarms.await()
                        }
                    }
                }
            withTimeout(5_000L) { twoPreWarmsStarted.await() }

            val notificationStarts = AtomicInteger(0)
            val bothNotificationsStarted = CompletableDeferred<Unit>()
            val notifications =
                List(2) {
                    async(Dispatchers.Default) {
                        gate.withPermit(AvatarFetchLane.NOTIFICATION) {
                            if (notificationStarts.incrementAndGet() == 2) {
                                bothNotificationsStarted.complete(Unit)
                            }
                        }
                    }
                }

            withTimeout(5_000L) { bothNotificationsStarted.await() }
            assertEquals(2, activePreWarms.get())

            releasePreWarms.complete(Unit)
            preWarms.awaitAll()
            notifications.awaitAll()
        }
    }

    @Test
    fun avatarDecodeSampleSizeLeavesSmallImagesAlone() {
        assertEquals(1, avatarDecodeSampleSize(width = 128, height = 256, maxDimension = 512))
    }

    @Test
    fun avatarDecodeSampleSizeDownsamplesLargeImagesByPowersOfTwo() {
        assertEquals(2, avatarDecodeSampleSize(width = 1024, height = 768, maxDimension = 512))
        assertEquals(8, avatarDecodeSampleSize(width = 4096, height = 1024, maxDimension = 512))
    }

    @Test
    fun avatarDecodeSampleSizeDownsamplesImagesJustOverTheCap() {
        // 513 / 1 = 513 still > 512, so sampleSize must advance to 2.
        assertEquals(2, avatarDecodeSampleSize(width = 513, height = 513, maxDimension = 512))
        assertEquals(2, avatarDecodeSampleSize(width = 513, height = 100, maxDimension = 512))
    }

    @Test
    fun avatarFailureFreshIsFalseWhenNoExpiry() {
        assertEquals(false, isAvatarFailureFresh(expiresAt = null, nowMillis = 1_000L))
    }

    @Test
    fun avatarFailureFreshIsTrueBeforeExpiry() {
        assertEquals(true, isAvatarFailureFresh(expiresAt = 2_000L, nowMillis = 1_000L))
    }

    @Test
    fun avatarFailureFreshIsFalseAtOrAfterExpiry() {
        assertEquals(false, isAvatarFailureFresh(expiresAt = 1_000L, nowMillis = 1_000L))
        assertEquals(false, isAvatarFailureFresh(expiresAt = 1_000L, nowMillis = 5_000L))
    }

    @Test
    fun avatarDecodeSampleSizeAcceptsExactBoundary() {
        // Source equal to the cap on the long edge — no downscale needed.
        assertEquals(1, avatarDecodeSampleSize(width = 512, height = 256, maxDimension = 512))
        assertEquals(1, avatarDecodeSampleSize(width = 512, height = 512, maxDimension = 512))
    }

    @Test
    fun avatarDecodeSampleSizeHandlesNonPowerOfTwoRatios() {
        // 1000 > 512 but 1000/2 = 500 ≤ 512, so sampleSize = 2 satisfies the
        // cap on the long edge even though the input isn't a clean power-of-
        // two scale of the maxDimension.
        assertEquals(2, avatarDecodeSampleSize(width = 1000, height = 1000, maxDimension = 512))
        // 1500/2 = 750 still > 512; need 1500/4 = 375.
        assertEquals(4, avatarDecodeSampleSize(width = 1500, height = 1500, maxDimension = 512))
    }

    @Test
    fun scaleAvatarBitmapClampsLongEdgeExactly() {
        assertEquals(512 to 256, avatarScaledDimensions(width = 900, height = 450, maxDimension = 512))
        assertEquals(256 to 512, avatarScaledDimensions(width = 450, height = 900, maxDimension = 512))
        assertEquals(512 to 512, avatarScaledDimensions(width = 700, height = 700, maxDimension = 512))
        assertEquals(512 to 256, avatarScaledDimensions(width = 564, height = 282, maxDimension = 512))
        assertEquals(128 to 256, avatarScaledDimensions(width = 128, height = 256, maxDimension = 512))
    }

    @Test
    fun avatarFailureExpiryCacheDropsExpiredEntriesWhenFull() {
        val failures = AvatarFailureExpiryCache(maxEntries = 3)
        failures.recordFailure(url = "https://example.com/stale-1.png", expiresAtMillis = 1_000L, nowMillis = 0L)
        failures.recordFailure(url = "https://example.com/stale-2.png", expiresAtMillis = 1_000L, nowMillis = 0L)
        failures.recordFailure(url = "https://example.com/stale-3.png", expiresAtMillis = 1_000L, nowMillis = 0L)

        failures.recordFailure(url = "https://example.com/fresh.png", expiresAtMillis = 3_000L, nowMillis = 2_000L)

        assertEquals(1, failures.size)
        assertEquals(false, failures.isFresh(url = "https://example.com/stale-1.png", nowMillis = 2_500L))
        assertEquals(true, failures.isFresh(url = "https://example.com/fresh.png", nowMillis = 2_500L))
    }

    @Test
    fun avatarFailureExpiryCacheDropsExpiredEntriesBeforeCapacity() {
        val failures = AvatarFailureExpiryCache(maxEntries = 3)
        failures.recordFailure(url = "https://example.com/stale.png", expiresAtMillis = 1_000L, nowMillis = 0L)

        failures.recordFailure(url = "https://example.com/fresh.png", expiresAtMillis = 3_000L, nowMillis = 2_000L)

        assertEquals(1, failures.size)
        assertEquals(false, failures.isFresh(url = "https://example.com/stale.png", nowMillis = 2_500L))
        assertEquals(true, failures.isFresh(url = "https://example.com/fresh.png", nowMillis = 2_500L))
    }

    @Test
    fun avatarFailureExpiryCacheEvictsOldestEntriesWhenFailuresRemainFresh() {
        val failures = AvatarFailureExpiryCache(maxEntries = 3)
        (1..5).forEach { index ->
            failures.recordFailure(
                url = "https://example.com/avatar-$index.png",
                expiresAtMillis = 10_000L,
                nowMillis = 0L,
            )
        }

        assertEquals(3, failures.size)
        assertEquals(false, failures.isFresh(url = "https://example.com/avatar-1.png", nowMillis = 1_000L))
        assertEquals(false, failures.isFresh(url = "https://example.com/avatar-2.png", nowMillis = 1_000L))
        assertEquals(true, failures.isFresh(url = "https://example.com/avatar-3.png", nowMillis = 1_000L))
        assertEquals(true, failures.isFresh(url = "https://example.com/avatar-4.png", nowMillis = 1_000L))
        assertEquals(true, failures.isFresh(url = "https://example.com/avatar-5.png", nowMillis = 1_000L))
    }
}
