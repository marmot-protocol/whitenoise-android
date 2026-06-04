package dev.ipf.darkmatter.core

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
}
