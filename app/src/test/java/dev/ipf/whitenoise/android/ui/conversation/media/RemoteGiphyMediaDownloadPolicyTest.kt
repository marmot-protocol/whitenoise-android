package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for automatic and explicit GIPHY retrieval admission. */
class RemoteGiphyMediaDownloadPolicyTest {
    @Test
    fun pausedAutomaticDownloadWaitsForUser() {
        assertFalse(
            shouldLoadRemoteGiphyMedia(
                automaticDownloadsPaused = true,
                automaticAllowed = true,
                manualRequest = false,
            ),
        )
    }

    @Test
    fun manualRequestBypassesAutomaticDownloadPause() {
        assertTrue(
            shouldLoadRemoteGiphyMedia(
                automaticDownloadsPaused = true,
                automaticAllowed = false,
                manualRequest = true,
            ),
        )
    }

    @Test
    fun allowedAutomaticDownloadStartsWithoutUserAction() {
        assertTrue(
            shouldLoadRemoteGiphyMedia(
                automaticDownloadsPaused = false,
                automaticAllowed = true,
                manualRequest = false,
            ),
        )
    }

    @Test
    fun disabledAutomaticDownloadWaitsForUser() {
        assertFalse(
            shouldLoadRemoteGiphyMedia(
                automaticDownloadsPaused = false,
                automaticAllowed = false,
                manualRequest = false,
            ),
        )
    }

    @Test
    fun cachedMediaLoadsWhileAutomaticDownloadsArePaused() {
        assertTrue(
            shouldLoadRemoteGiphyMedia(
                automaticDownloadsPaused = true,
                automaticAllowed = false,
                manualRequest = false,
                cachedAvailable = true,
            ),
        )
    }

    /** Cached/manual download permission does not override the independent global playback pause. */
    @Test
    fun cachedOrManuallyLoadedMediaStaysStillWhilePlaybackIsPaused() {
        assertTrue(shouldLoadRemoteGiphyMedia(true, false, true, cachedAvailable = true))
        assertFalse(inlineAnimationEligible(false, true, true, true, true, true))
    }

    /** Each eligibility input independently withdraws a row's animation grant. */
    @Test
    fun inlinePlaybackNeedsEveryMotionAndLifetimeGate() {
        assertTrue(inlineAnimationEligible(true, true, true, true, true, true))
        for (missing in 0 until 6) {
            val flags = BooleanArray(6) { it != missing }
            assertFalse(inlineAnimationEligible(flags[0], flags[1], flags[2], flags[3], flags[4], flags[5]))
        }
    }

    /** Cached bytes and manual consent cannot admit work under a retired account/runtime owner. */
    @Test
    fun retiredOwnerCannotUseCachedOrManuallyRequestedMedia() {
        assertFalse(shouldLoadRemoteGiphyMedia(false, true, true, cachedAvailable = true, ownerEligible = false))
        assertTrue(shouldLoadRemoteGiphyMedia(true, false, false, cachedAvailable = true, ownerEligible = true))
    }

    @Test
    fun byteCacheRetainsRecentMediaAndEvictsLeastRecentlyUsedBytes() {
        val cache = RemoteGiphyByteCache(maxBytes = 5)
        cache.put("first", byteArrayOf(1, 2, 3))
        cache.put("second", byteArrayOf(4, 5))

        assertTrue(cache.get("first") != null)

        cache.put("third", byteArrayOf(6))

        assertTrue(cache.get("first") != null)
        assertTrue(cache.get("second") == null)
        assertTrue(cache.get("third") != null)
    }
}
