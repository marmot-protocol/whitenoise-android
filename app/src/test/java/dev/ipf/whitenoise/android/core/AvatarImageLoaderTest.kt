package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarImageLoaderTest {
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
