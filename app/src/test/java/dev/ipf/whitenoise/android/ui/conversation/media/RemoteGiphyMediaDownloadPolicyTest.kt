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
}
