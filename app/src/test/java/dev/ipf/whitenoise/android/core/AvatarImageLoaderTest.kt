package dev.ipf.whitenoise.android.core

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AvatarImageLoaderTest {
    /** A pre-recovery socket failure cannot recreate the old minute-long cooldown afterward. */
    @Test
    fun lateFailureCannotPoisonRecoveredRequest() =
        runBlocking {
            val oldCalls = AtomicInteger()
            val started = AtomicInteger()
            val bothStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val releaseOther = CompletableDeferred<Unit>()
            val barrierStarted = CompletableDeferred<Unit>()
            val oldUrl = "https://profiles.example/late-old"
            AvatarImageLoader.attachProfileImageFetcher { url, _ ->
                if (url == oldUrl && oldCalls.incrementAndGet() == 1) {
                    if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                    releaseOld.await()
                    error("late offline failure")
                }
                if (url.endsWith("held")) {
                    if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                    releaseOther.await()
                    error("other offline failure")
                }
                if (url.endsWith("barrier")) barrierStarted.complete(Unit)
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }
            try {
                AvatarImageLoader.preWarm(oldUrl)
                AvatarImageLoader.preWarm("https://profiles.example/held")
                withTimeout(5_000) { bothStarted.await() }
                AvatarLoadRecovery.onNetworkRestored()
                AvatarImageLoader.preWarm("https://profiles.example/barrier")
                releaseOld.complete(Unit)
                // Prewarm holds its admission until detached completion has published. This
                // later prewarm therefore proves the old failure finished before the assertion.
                withTimeout(5_000) { barrierStarted.await() }
                assertNotNull(withTimeout(5_000) { AvatarImageLoader.load(oldUrl) })
                assertEquals(2, oldCalls.get())
            } finally {
                releaseOld.complete(Unit)
                releaseOther.complete(Unit)
            }
        }

    /** Recovering connectivity never turns a successful picture back into an empty placeholder. */
    @Test
    fun recoveryPreservesDecodedPixelsWithoutFetchingAgain() =
        runBlocking {
            val calls = AtomicInteger()
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                calls.incrementAndGet()
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }
            val url = "https://profiles.example/cached"
            val image = AvatarImageLoader.load(url)
            assertNotNull(image)
            AvatarLoadRecovery.onNetworkRestored()
            assertSame(image, AvatarImageLoader.peek(url))
            assertSame(image, AvatarImageLoader.load(url))
            assertEquals(1, calls.get())
        }

    /** Recovery retires queued requests before new callers can join them or duplicate their sockets. */
    @Test
    fun recoveryDetachesOldWaitersAndSkipsTheirQueuedFetches() =
        runBlocking {
            val occupied = AtomicInteger()
            val bothOccupied = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val targetCalls = AtomicInteger()
            AvatarImageLoader.attachProfileImageFetcher { url, _ ->
                if (url.contains("blocker")) {
                    if (occupied.incrementAndGet() == 2) bothOccupied.complete(Unit)
                    release.await()
                    error("old offline socket")
                }
                targetCalls.incrementAndGet()
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }
            val blockers =
                List(2) { index ->
                    async { AvatarImageLoader.load("https://profiles.example/blocker$index") }
                }
            try {
                withTimeout(5_000) { bothOccupied.await() }
                val oldQueued =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        AvatarImageLoader.load("https://profiles.example/queued")
                    }
                AvatarLoadRecovery.onNetworkRestored()
                assertNull(withTimeout(5_000) { oldQueued.await() })
                val current =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        AvatarImageLoader.load("https://profiles.example/queued")
                    }
                release.complete(Unit)
                assertNotNull(withTimeout(5_000) { current.await() })
                assertEquals(1, targetCalls.get())
                blockers.awaitAll().forEach { assertNull(it) }
            } finally {
                release.complete(Unit)
            }
        }

    /** A validated recovery retries a failed URL immediately, without its minute-long cooldown. */
    @Test
    fun validatedRecoveryMakesFailedAvatarAvailableImmediately() =
        runBlocking {
            var online = false
            val attempts = AtomicInteger()
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                attempts.incrementAndGet()
                check(online) { "synthetic offline fetch" }
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }
            val url = "https://profiles.example/recovery.png"
            assertNull(AvatarImageLoader.load(url))
            online = true
            assertNull(AvatarImageLoader.load(url))
            assertEquals(1, attempts.get())

            AvatarLoadRecovery.onNetworkRestored()

            assertNotNull(AvatarImageLoader.load(url))
            assertEquals(2, attempts.get())
        }

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

    /** An avatar entry can never answer a banner request for the same URL (#2762). */
    @Test
    fun cachedAvatarNeverSatisfiesABannerRequest() =
        runBlocking {
            val fetches = AtomicInteger()
            val url = "https://profiles.example/variant-banner.png"
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                fetches.incrementAndGet()
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }

            assertNotNull(AvatarImageLoader.load(url))
            assertNull("an avatar entry must not be visible to a banner peek", AvatarImageLoader.peekBanner(url, 1080))
            assertNotNull(AvatarImageLoader.loadBanner(url, 1080))

            assertEquals(2, fetches.get())
            assertNotNull(AvatarImageLoader.peek(url))
            assertNotNull(AvatarImageLoader.peekBanner(url, 1080))
        }

    /** Banner widths that round into one bucket share a decode; different buckets keep their own. */
    @Test
    fun compatibleBannerRequestsCoalesceWhileDifferentBucketsDoNot() =
        runBlocking {
            val fetches = AtomicInteger()
            val released = CompletableDeferred<Unit>()
            val url = "https://profiles.example/coalescing-banner.png"
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                fetches.incrementAndGet()
                released.await()
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }

            val compatible =
                listOf(1030, 1080, 1280).map { width ->
                    async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        AvatarImageLoader.loadBanner(url, width)
                    }
                }
            released.complete(Unit)
            withTimeout(5_000) { compatible.awaitAll() }
            assertEquals("widths in one bucket must share a single fetch", 1, fetches.get())

            assertNotNull(withTimeout(5_000) { AvatarImageLoader.loadBanner(url, 1400) })
            assertEquals("a wider bucket needs its own bounded decode", 2, fetches.get())
        }

    /** Account teardown retires every variant, not just the avatar entries. */
    @Test
    fun clearRetiresBannerEntriesAlongsideAvatars() =
        runBlocking {
            val url = "https://profiles.example/teardown-banner.png"
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }
            assertNotNull(AvatarImageLoader.load(url))
            assertNotNull(AvatarImageLoader.loadBanner(url, 1080))

            AvatarImageLoader.clear()

            assertNull(AvatarImageLoader.peek(url))
            assertNull(AvatarImageLoader.peekBanner(url, 1080))
        }

    /** Only the banner variant carries its target in the cache key, so avatar entries are untouched. */
    @Test
    fun profileImageCacheKeysSeparateVariantsAndTargets() {
        val url = "https://profiles.example/keys.png"
        assertEquals(url, profileImageCacheKey(url, ProfileImageVariant.AVATAR, 512))
        assertNotEquals(
            profileImageCacheKey(url, ProfileImageVariant.AVATAR, 512),
            profileImageCacheKey(url, ProfileImageVariant.BANNER, 512),
        )
        assertNotEquals(
            profileImageCacheKey(url, ProfileImageVariant.BANNER, 1024),
            profileImageCacheKey(url, ProfileImageVariant.BANNER, 1280),
        )
    }

    /** Requested widths round up into shared buckets, floored at the avatar cap and capped overall. */
    @Test
    fun bannerDecodeDimensionBucketsAndBoundsTheRequestedWidth() {
        assertEquals(512, profileBannerDecodeDimension(0))
        assertEquals(512, profileBannerDecodeDimension(-100))
        assertEquals(512, profileBannerDecodeDimension(400))
        assertEquals(1024, profileBannerDecodeDimension(1024))
        assertEquals(1280, profileBannerDecodeDimension(1030))
        assertEquals(1280, profileBannerDecodeDimension(1080))
        assertEquals(PROFILE_BANNER_MAX_DIMENSION, profileBannerDecodeDimension(4000))
    }

    /** A wide banner keeps its full target, while a near-square source is cut back to fit the byte budget. */
    @Test
    fun boundedDecodeDimensionHonoursTheDecodedByteBudget() {
        assertEquals(1536, boundedDecodeDimension(width = 4000, height = 2000, maxDimension = 1536))
        assertEquals(768, boundedDecodeDimension(width = 4000, height = 4000, maxDimension = 1536))
        assertEquals(512, boundedDecodeDimension(width = 4000, height = 4000, maxDimension = 512))
    }

    /**
     * Banner pressure past the whole former budget still cannot evict a cached avatar (#2762).
     *
     * The banner entries published here total more than a single combined cache could have held, so
     * on the shared LRU this replaced the avatar — least recently used of the lot — would have been
     * the first thing dropped. The oldest banner going missing is what proves the pressure was real
     * rather than the budget quietly absorbing it.
     */
    @Test
    fun bannerEntriesCannotEvictTheCachedAvatarWorkingSet() {
        val avatarUrl = "https://profiles.example/pressure-avatar.png"
        val bannerUrl = "https://profiles.example/pressure-banner.png"
        AvatarImageLoader.putCached(avatarUrl, solidImage(AVATAR_EDGE_PX, AVATAR_EDGE_PX))
        val widths = (1..BANNER_PRESSURE_ENTRIES).map { it * PROFILE_BANNER_TARGET_BUCKET_PX }

        widths.forEach { width ->
            AvatarImageLoader.putCachedBanner(bannerUrl, width, solidImage(BANNER_EDGE_PX, BANNER_EDGE_PX))
        }

        assertNotNull(
            "a banner run larger than the whole former cache must not evict an avatar",
            AvatarImageLoader.peek(avatarUrl),
        )
        assertNull(
            "the oldest banner must have been evicted, or the run applied no pressure at all",
            AvatarImageLoader.peekBanner(bannerUrl, widths.first()),
        )
        assertNotNull(
            "the newest banner stays cached within the banner budget",
            AvatarImageLoader.peekBanner(bannerUrl, widths.last()),
        )
    }

    /** Each variant is charged only to its own budget, and evicts only its own entries. */
    @Test
    fun partitionedCacheHoldsEachVariantToItsOwnBudget() {
        val entry = solidImage(AVATAR_EDGE_PX, AVATAR_EDGE_PX)
        val entryBytes = entry.asAndroidBitmap().byteCount
        val cache = PartitionedProfileImageCache(avatarBytes = entryBytes * 2, bannerBytes = entryBytes)
        val avatarKey = profileImageCacheKey("https://profiles.example/a.png", ProfileImageVariant.AVATAR, 512)
        val firstBanner = profileImageCacheKey("https://profiles.example/a.png", ProfileImageVariant.BANNER, 1024)
        val secondBanner = profileImageCacheKey("https://profiles.example/a.png", ProfileImageVariant.BANNER, 1280)

        cache.put(avatarKey, entry)
        cache.put(firstBanner, entry)
        cache.put(secondBanner, entry)

        assertNotNull("the banner budget filling up cannot reach the avatar partition", cache.get(avatarKey))
        assertNull("a banner evicts only the banner before it", cache.get(firstBanner))
        assertNotNull(cache.get(secondBanner))
        assertEquals(entryBytes, cache.byteSize(ProfileImageVariant.AVATAR))
        assertEquals(entryBytes, cache.byteSize(ProfileImageVariant.BANNER))
    }

    /** A cache key resolves back to the variant it was built for, which is what picks its partition. */
    @Test
    fun cacheKeysResolveBackToTheirVariant() {
        val url = "https://profiles.example/variant-of.png"
        assertEquals(
            ProfileImageVariant.AVATAR,
            profileImageVariantOf(profileImageCacheKey(url, ProfileImageVariant.AVATAR, 512)),
        )
        assertEquals(
            ProfileImageVariant.BANNER,
            profileImageVariantOf(profileImageCacheKey(url, ProfileImageVariant.BANNER, 1280)),
        )
    }

    /** An opaque ARGB_8888 bitmap of the given size, sized for the cache's byte budgets. */
    private fun solidImage(
        width: Int,
        height: Int,
    ): ImageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

    private companion object {
        const val AVATAR_EDGE_PX = 512
        const val BANNER_EDGE_PX = 1024

        // 4MB each against the 8MB banner budget, and 32MB in total — more than
        // the 24MB a single combined cache would ever have held.
        const val BANNER_PRESSURE_ENTRIES = 8

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
