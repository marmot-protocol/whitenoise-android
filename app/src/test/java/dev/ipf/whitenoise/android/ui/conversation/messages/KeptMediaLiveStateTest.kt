package dev.ipf.whitenoise.android.ui.conversation.messages

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AttachmentLocalAssetFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.state.mediaCacheKey
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        awaitPresentation { presentation?.attachments?.singleOrNull()?.statusLabel == "Downloading" }
        native.publish(AttachmentTransferStateFfi.FAILED)
        awaitPresentation {
            presentation?.attachments?.singleOrNull()?.statusLabel == "Download failed. Open the original to retry"
        }
        composeRule.onNodeWithText("Audio · Download failed. Open the original to retry").assertIsDisplayed()
        native.publish(AttachmentTransferStateFfi.REMOVED)
        awaitPresentation {
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
        awaitPresentation {
            presentation?.attachments?.singleOrNull()?.statusLabel == "Download failed. Open the original to retry"
        }
        composeRule.onNodeWithText("Video · Download failed. Open the original to retry").assertIsDisplayed()
        assertNoAcquisition(native)
    }

    /** A completed cached observer must reopen when eviction reveals native failure behind the kept card. */
    @Test
    fun cacheEvictionRestartsReadOnlyNativeObservation() {
        val surface = surface("video/mp4")
        val native = KeptMediaNativeFixture(surface.appState, AttachmentTransferStateFfi.FAILED)
        val cacheKey = mediaCacheKey(SWIPE_TEST_ACCOUNT_REF, SWIPE_TEST_GROUP_ID, SWIPE_TEST_MESSAGE_ID, 0)
        surface.appState.cacheMediaPlaintext(cacheKey, byteArrayOf(1, 2, 3))
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
        composeRule.runOnIdle {
            val status = presentation!!.attachments.single().statusLabel
            assertEquals("Available on this device", status)
        }
        composeRule.runOnIdle { surface.appState.removeMediaMemoryCacheEntry(cacheKey) }
        awaitPresentation {
            // Native observation probes Android's main-thread cache again after the disk miss.
            presentation?.attachments?.singleOrNull()?.statusLabel == "Download failed. Open the original to retry"
        }
        assertNoAcquisition(native)
    }

    /** A native failure cannot replace verified own-file availability while competing probes are unresolved. */
    @Test
    fun firstOwnFileProbeCommitsBeforeNativeFailureCanAppear() {
        val surface = surface("video/mp4", mine = true)
        val firstProbe = CountDownLatch(1)
        val laterProbe = CountDownLatch(1)
        val probes = AtomicInteger()
        val native =
            KeptMediaNativeFixture(surface.appState, AttachmentTransferStateFfi.FAILED) {
                val gate = if (probes.incrementAndGet() == 1) firstProbe else laterProbe
                check(gate.await(10, TimeUnit.SECONDS))
                AttachmentLocalAssetFfi("retained-fixture", 3u)
            }
        var presentation: KeptMessagePresentation? = null
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    KeptMediaTestHost(
                        surface.item,
                        conversation = surface.controller,
                        onPresentation = { presentation = it },
                    )
                }
            }
            awaitPresentation { "subscribeAttachmentTransfers" in native.calls && probes.get() > 0 }
            composeRule.runOnIdle {
                assertEquals("Checking availability", presentation!!.attachments.single().statusLabel)
            }
            firstProbe.countDown()
            awaitPresentation {
                presentation?.attachments?.singleOrNull()?.statusLabel != "Checking availability"
            }
            composeRule.runOnIdle {
                assertEquals("Available on this device", presentation!!.attachments.single().statusLabel)
            }
            assertEquals(1, probes.get())
        } finally {
            firstProbe.countDown()
            laterProbe.countDown()
        }
    }

    /** Native callbacks post through Android's paused main looper as well as the Compose test scheduler. */
    private fun awaitPresentation(condition: () -> Boolean) {
        composeRule.waitUntil(5_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(16))
            condition()
        }
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
