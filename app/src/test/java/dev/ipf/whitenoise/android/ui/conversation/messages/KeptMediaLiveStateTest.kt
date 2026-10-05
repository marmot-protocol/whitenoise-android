package dev.ipf.whitenoise.android.ui.conversation.messages

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises controller-backed observation, including updates after the kept card's first frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w400dp-h800dp-mdpi")
class KeptMediaLiveStateTest {
    @get:Rule val composeRule = createComposeRule()

    /** Decoding can publish after plaintext hydration; no unrelated cache mutation is necessary. */
    @Test
    fun lateThumbnailPublicationUpdatesTheVisibleCard() {
        val surface = surface("image/png")
        var presentation: KeptMessagePresentation? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                KeptMediaTestHost(
                    surface.item,
                    conversation = surface.controller,
                    onPresentation = { presentation = it },
                )
            }
        }
        composeRule.runOnIdle { assertNull(presentation!!.attachments.single().thumbnail) }
        val plaintextRevision = surface.appState.mediaCacheRevision.value
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        composeRule.runOnIdle { surface.controller.cacheThumbnail(SWIPE_TEST_MESSAGE_ID, 0, bitmap) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertSame(
                bitmap,
                presentation!!
                    .attachments
                    .single()
                    .thumbnail
                    ?.asAndroidBitmap(),
            )
            assertEquals(plaintextRevision, surface.appState.mediaCacheRevision.value)
        }
        val replacement = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
        composeRule.runOnIdle { surface.controller.cacheThumbnail(SWIPE_TEST_MESSAGE_ID, 0, replacement) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertSame(
                replacement,
                presentation!!
                    .attachments
                    .single()
                    .thumbnail
                    ?.asAndroidBitmap(),
            )
            surface.appState.mediaMemoryCacheKeysSnapshot().forEach(surface.appState::removeMediaMemoryCacheEntry)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertNull(presentation!!.attachments.single().thumbnail) }
    }

    /** Native source-based audio work has no host plaintext waiter to announce downloading or failure. */
    @Test
    fun nativeOnlyAudioTransferUpdatesTheVisibleCard() {
        val surface = surface("audio/ogg")
        val native = KeptMediaNativeFixture(surface.appState, AttachmentTransferStateFfi.DOWNLOADING)
        var presentation: KeptMessagePresentation? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                KeptMediaTestHost(
                    surface.item,
                    conversation = surface.controller,
                    onPresentation = { presentation = it },
                )
            }
        }
        composeRule.waitUntil(5_000) { presentation?.attachments?.singleOrNull()?.statusLabel == "Downloading" }
        native.publish(AttachmentTransferStateFfi.FAILED)
        composeRule.waitUntil(5_000) {
            presentation?.attachments?.singleOrNull()?.statusLabel == "Download failed. Open the original to retry"
        }
        composeRule.onNodeWithText("Audio · Download failed. Open the original to retry").assertIsDisplayed()
        native.publish(AttachmentTransferStateFfi.REMOVED)
        composeRule.waitUntil(5_000) {
            presentation?.attachments?.singleOrNull()?.statusLabel == "Attachment unavailable"
        }
        assertNoAcquisition(native)
    }

    /** A sent video with an existing native failure is observed even when no host download was started. */
    @Test
    fun ownVideoShowsExistingNativeFailure() {
        val surface = surface("video/mp4", mine = true)
        val native = KeptMediaNativeFixture(surface.appState, AttachmentTransferStateFfi.FAILED)
        var presentation: KeptMessagePresentation? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                KeptMediaTestHost(
                    surface.item,
                    conversation = surface.controller,
                    onPresentation = { presentation = it },
                )
            }
        }
        composeRule.waitUntil(5_000) {
            presentation?.attachments?.singleOrNull()?.statusLabel == "Download failed. Open the original to retry"
        }
        composeRule.onNodeWithText("Video · Download failed. Open the original to retry").assertIsDisplayed()
        assertNoAcquisition(native)
    }

    /** Loads the accepted reference into the actual controller, not just the presentation input. */
    private fun surface(
        mediaType: String,
        mine: Boolean = false,
    ): SwipeTestSurface {
        val surface = swipeTestSurface(ApplicationProvider.getApplicationContext(), false, mine, true)
        val record = surface.item.projected!!
        val reference = MessageAttachments.acceptedReferences(record.media).single().copy(mediaType = mediaType)
        runBlocking {
            surface.controller.testRefreshCurrentTimeline(SWIPE_TEST_ACCOUNT_REF) {
                TimelinePageFfi(
                    listOf(record.copy(media = MessageAttachments.acceptedOutcomes(listOf(reference)))),
                    false,
                    false,
                )
            }
        }
        return surface.copy(item = surface.controller.timeline.single())
    }

    /** Keeping a card must never request, retry, cancel, download or read attachment bytes. */
    private fun assertNoAcquisition(native: KeptMediaNativeFixture) {
        val allowed = setOf("displayName", "recordHostTiming", "attachmentLocalAssets", "subscribeAttachmentTransfers")
        assertFalse(native.calls.toString(), native.calls.any { it !in allowed })
    }
}
