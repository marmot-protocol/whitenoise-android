package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.NotificationSuppression
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.requestAttachmentRetry
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/** Native failure and a tile's old plaintext waiter may arrive on opposite sides of the user's Retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoTileRetryRaceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val request = MediaDownloadIntegrationFixture.request(0)
    private val reference =
        MediaDownloadIntegrationFixture.reference(0).copy(mediaType = "video/mp4", fileName = "clip.mp4")
    private val oldResult = CompletableDeferred<File>()
    private val sharedLoad = SingleFlight<Unit, File>()
    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController
    private lateinit var readyFile: File
    private var resolutions = 0
    private var opens = 0

    /** Retains a native Failed snapshot while deliberately delaying its old plaintext waiter. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                fixture.state,
                conversationTimelineTestGroup().copy(groupIdHex = request.groupIdHex),
            )
        WhiteNoiseAppState::class.java.getDeclaredField("suppression").apply { isAccessible = true }.set(
            fixture.state,
            NotificationSuppression().onForeground().onActiveConversation(request.groupIdHex, request.accountRef),
        )
        fixture.state.attachmentOpens.setDestination(
            AttachmentOpenDestination(request.accountRef, request.groupIdHex, navigationGeneration = 1L),
        )
        runBlocking { fixture.state.requestAttachmentRetry(request) }
        // Native progress can report failure before the independently observed plaintext result finishes.
        fixture.calls.single().failure = IOException("old native attempt failed")
        readyFile = File.createTempFile("video-retry-", ".mp4", context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
    }

    /** Releases a deliberately held result before disposing this test's controller and native boundary. */
    @After
    fun tearDown() {
        oldResult.completeExceptionally(IOException("fixture closed"))
        controller.onCleared()
        fixture.close()
        readyFile.delete()
    }

    /** A native Retry restarts the single-video waiter even before its local failure flag arrives. */
    @Test
    fun bubbleRetryBeforeLocalFailureReplacesOldWaiter() = assertRetry(album = false, lateFailure = true)

    /** Album videos obey the same ordering contract as single-video bubbles. */
    @Test
    fun albumRetryBeforeLocalFailureReplacesOldWaiter() = assertRetry(album = true, lateFailure = true)

    /** An already-delivered local failure still admits exactly one native retry. */
    @Test
    fun bubbleRetryAfterLocalFailureAdmitsOnce() = assertRetry(album = false, lateFailure = false)

    /** Album retries cannot admit twice when both native and local failure are visible. */
    @Test
    fun albumRetryAfterLocalFailureAdmitsOnce() = assertRetry(album = true, lateFailure = false)

    /** Rejected admission must retain the failed bubble and must not open a viewer or start another waiter. */
    @Test
    fun bubbleRejectedRetryDoesNotRestartOrOpen() = assertRejectedRetry(album = false)

    /** Rejected admission has the same no-handoff guarantee for album tiles. */
    @Test
    fun albumRejectedRetryDoesNotRestartOrOpen() = assertRejectedRetry(album = true)

    /** Forces the old result to arrive before or after admission, then checks readiness and the exact handoff count. */
    private fun assertRetry(
        album: Boolean,
        lateFailure: Boolean,
    ) {
        hostAndStart(album)
        if (!lateFailure) {
            composeRule.runOnIdle { oldResult.completeExceptionally(IOException("old local failure")) }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithContentDescription(text(R.string.media_tap_to_retry)).performClick()
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            opens == 2
        }
        if (lateFailure) {
            composeRule.runOnIdle { oldResult.completeExceptionally(IOException("late old local failure")) }
        }
        composeRule.waitUntil(WAIT_MILLIS) { resolutions == 2 && shows(R.string.reply_media_video) }
        composeRule.onNodeWithContentDescription(text(R.string.reply_media_video)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, fixture.retryDemands.get())
            assertEquals(2, opens)
            assertEquals(2, resolutions)
        }
        // The old progress observer still reports Failed; a verified local Open must not admit another retry.
        tapVideo(album)
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            opens == 3
        }
        composeRule.runOnIdle { assertEquals(1, fixture.retryDemands.get()) }
    }

    /** Rejects the native command after presentation and proves that rejection cannot create a new loader. */
    private fun assertRejectedRetry(album: Boolean) {
        hostAndStart(album)
        fixture.explicitDemandFailure = IOException("retry rejected")
        composeRule.onNodeWithContentDescription(text(R.string.media_tap_to_retry)).performClick()
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            fixture.retryDemands.get() == 1 &&
                fixture.state.attachmentUserActions.pendingRetries.value
                    .isEmpty()
        }
        composeRule.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, resolutions)
        }
        composeRule.onNodeWithContentDescription(text(R.string.media_tap_to_retry)).assertIsDisplayed()
    }

    /** Runs a real production tile with only file materialization held at a controlled coroutine boundary. */
    private fun hostAndStart(album: Boolean) {
        val resolver: VideoViewerFileResolver = { _, _, _, _, _, _, _ ->
            sharedLoad.run(Unit) {
                resolutions++
                if (resolutions == 1) oldResult.await() else readyFile
            }
        }
        composeRule.setContent {
            WhiteNoiseTheme {
                if (album) {
                    MediaVideoGridTile(
                        messageIdHex = request.messageIdHex,
                        attachmentIndex = 0,
                        reference = reference,
                        controller = controller,
                        appState = fixture.state,
                        mine = false,
                        onTap = { opens++ },
                        overflowCount = 0,
                        modifier = Modifier.size(160.dp).testTag(TILE_TAG),
                        videoFileResolver = resolver,
                    )
                } else {
                    MediaVideoBubble(
                        item = localTimelineMessage(timelineAppMessage(request.messageIdHex)),
                        attachmentIndex = 0,
                        reference = reference,
                        controller = controller,
                        appState = fixture.state,
                        mine = false,
                        onOpenConversationMedia = { opens++ },
                        videoFileResolver = resolver,
                    )
                }
            }
        }
        tapVideo(album)
        composeRule.waitUntil(WAIT_MILLIS) { resolutions == 1 && shows(R.string.media_tap_to_retry) }
    }

    /** Uses the same open affordance before downloading and after the video becomes local. */
    private fun tapVideo(album: Boolean) {
        if (album) {
            composeRule.onNodeWithTag(TILE_TAG).performTouchInput { click() }
        } else {
            composeRule.onNodeWithTag(videoAttachmentOpenTestTag(request.messageIdHex, 0)).performClick()
        }
    }

    /** Queries the current accessible tile state without relying on elapsed animation time. */
    private fun shows(id: Int): Boolean {
        val nodes = composeRule.onAllNodesWithContentDescription(text(id))
        return nodes.fetchSemanticsNodes().isNotEmpty()
    }

    /** Uses the same localized strings as the production affordances. */
    private fun text(id: Int): String = context.getString(id)

    private companion object {
        const val TILE_TAG = "video-retry-tile"
        const val WAIT_MILLIS = 10_000L
    }
}
