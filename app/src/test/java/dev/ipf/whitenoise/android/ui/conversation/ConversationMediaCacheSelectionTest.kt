package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.mediaCacheKey
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerPage
import dev.ipf.whitenoise.android.ui.conversation.media.rememberMediaViewerForwardActions
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * App-wide media cache changes reach the conversation screen only where they matter: the whole-timeline selection
 * pass while a selection exists, and media-viewer forwarding where a payload is built (#3523).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationMediaCacheSelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var controller: ConversationController? = null

    /** Removes the counting tracer and releases the conversation's jobs. */
    @After
    @OptIn(InternalComposeTracingApi::class)
    fun releaseFixture() {
        Composer.setTracer(null)
        controller?.onCleared()
    }

    /** Downloads and evictions elsewhere in the app do not recompose the screen while nothing is selected. */
    @Test
    fun cacheChangesWhileReadingDoNotRecomposeTheScreen() {
        val conversation = mountScreen(ConversationSurfaceState())
        composeRule.mainClock.autoAdvance = false
        val screenRuns =
            countCompositions(SCREEN_SOURCE) {
                repeat(3) { index ->
                    composeRule.runOnIdle {
                        conversation.appState.cacheMediaPlaintext("other|chat|$index|0", byteArrayOf(index.toByte()))
                    }
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.waitForIdle()
                }
            }
        assertEquals("ConversationScreen.kt scopes re-ran for unrelated cache changes", 0, screenRuns)
    }

    /** A selected media message without a locator becomes forwardable once its bytes reach the cache. */
    @Test
    fun selectedMediaMessageBecomesForwardableWhenItsBytesAreCached() {
        val surface = ConversationSurfaceState()
        val conversation = mountScreen(surface)
        selectMediaThroughItsActions()
        composeRule.waitUntil(5_000) {
            surface.selectedMessages[MEDIA_ID]?.action?.forwardBlockedReason ==
                ForwardBlockedReason.UnavailableAttachment
        }
        composeRule.runOnIdle { cacheMediaBytes(conversation) }
        composeRule.waitUntil(5_000) { surface.selectedMessages[MEDIA_ID]?.action?.forwardPayload != null }
        assertNull(surface.selectedMessages[MEDIA_ID]?.action?.forwardBlockedReason)
    }

    /** A download that lands while nothing is selected still counts when the message is selected afterwards. */
    @Test
    fun selectingAfterABackgroundDownloadUsesTheCurrentCache() {
        val surface = ConversationSurfaceState()
        val conversation = mountScreen(surface)
        composeRule.runOnIdle { cacheMediaBytes(conversation) }
        composeRule.waitForIdle()
        selectMediaThroughItsActions()
        composeRule.waitUntil(5_000) { surface.selectedMessages[MEDIA_ID]?.action?.forwardPayload != null }
        assertNull(surface.selectedMessages[MEDIA_ID]?.action?.forwardBlockedReason)
    }

    /** The media viewer's Forward action still follows the cache once the lookup leaves the remember key. */
    @Test
    fun viewerForwardFollowsTheCache() {
        val conversation = conversation()
        val page = MediaViewerPage(MEDIA_ID, 0, LOCATORLESS_PHOTO, mine = false, SENDER, recordedAt = 2uL)
        composeRule.setContent {
            val actions = rememberMediaViewerForwardActions(conversation, conversation.appState)
            Text(if (actions.canForward(page)) "forwardable" else "blocked")
        }
        composeRule.onNodeWithText("blocked").assertExists()
        composeRule.runOnIdle { cacheMediaBytes(conversation) }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("forwardable").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Long-presses the media message and chooses Select, as a reader starts a batch selection. */
    private fun selectMediaThroughItsActions() {
        composeRule.onNodeWithTag(messageBubbleRowTestTag(MEDIA_ID)).performTouchInput { longClick() }
        val select = ApplicationProvider.getApplicationContext<Context>().getString(R.string.select)
        composeRule.onNodeWithText(select).performClick()
        composeRule.waitForIdle()
    }

    /** A kept media card still observes cache changes, so its attachment state refreshes while it is shown. */
    @Test
    fun keptMediaCardStillObservesCacheChanges() {
        val conversation = mountScreen(ConversationSurfaceState())
        composeRule.onNodeWithTag(messageBubbleRowTestTag(MEDIA_ID)).performTouchInput { longClick() }
        val keep = ApplicationProvider.getApplicationContext<Context>().getString(R.string.floating_keep)
        composeRule.onNodeWithText(keep).performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        val keptRuns =
            countCompositions(KEPT_ATTACHMENT_SOURCE) {
                composeRule.runOnIdle { cacheMediaBytes(conversation) }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
        assertTrue("the kept card's attachment did not re-run for its own download", keptRuns > 0)
    }

    /** Caches the media message's only attachment, as a completed download would. */
    private fun cacheMediaBytes(conversation: ConversationController) {
        val key =
            mediaCacheKey(
                requireNotNull(conversation.boundAccountRef),
                conversation.group.groupIdHex,
                MEDIA_ID,
                attachmentIndex = 0,
            )
        assertTrue(conversation.appState.cacheMediaPlaintext(key, byteArrayOf(1, 2, 3)))
    }

    /** Starts a conversation over two text messages around one locator-less media message. */
    private fun conversation(): ConversationController {
        val group = conversationTimelineTestGroup()
        val records =
            listOf(
                timelineRecord("a1".repeat(32), 1uL, "First message"),
                timelineRecord(MEDIA_ID, 2uL, plaintext = "").copy(
                    media = MessageAttachments.acceptedOutcomes(listOf(LOCATORLESS_PHOTO)),
                    sourceEpoch = LOCATORLESS_PHOTO.sourceEpoch,
                ),
                timelineRecord("a3".repeat(32), 3uL, "Last message"),
            )
        val subscription = ScriptedConversationTimelineSubscription(timelinePage(*records.toTypedArray()))
        val scripted = ScriptedConversationLiveSubscriptions(listOf(subscription), group)
        val conversation =
            ConversationController(
                appState = conversationTimelineTestAppState(scripted.subscriptions),
                initialGroup = group,
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
            )
        controller = conversation
        awaitConversationCondition { conversation.timeline.size == records.size }
        return conversation
    }

    /** Mounts the production conversation route with a test-owned surface state. */
    private fun mountScreen(surface: ConversationSurfaceState): ConversationController {
        val conversation = conversation()
        val group = conversationTimelineTestGroup()
        val members = conversationTimelineMemberSnapshot()
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = conversation.appState,
                    chat = ChatListItem(group, null, null, 1, memberSnapshot = members),
                    controller = conversation,
                    onBack = {},
                    surfaceState = surface,
                )
            }
        }
        composeRule.waitForIdle()
        return conversation
    }

    /** Counts runs of composable bodies and lambdas whose source location contains [source] during [block]. */
    @OptIn(InternalComposeTracingApi::class)
    private fun countCompositions(
        source: String,
        block: () -> Unit,
    ): Int {
        var runs = 0
        Composer.setTracer(
            object : CompositionTracer {
                /** Traces every body while installed. */
                override fun isTraceInProgress() = true

                /** Counts a body declared in the requested source file. */
                override fun traceEventStart(
                    key: Int,
                    dirty1: Int,
                    dirty2: Int,
                    info: String,
                ) {
                    if (source in info) runs++
                }

                /** Body ends need no bookkeeping for a count. */
                override fun traceEventEnd() = Unit
            },
        )
        try {
            block()
        } finally {
            Composer.setTracer(null)
        }
        return runs
    }

    private companion object {
        const val SCREEN_SOURCE = "(ConversationScreen.kt:"
        const val KEPT_ATTACHMENT_SOURCE = "(KeptAttachmentPresentations.kt:"
        val MEDIA_ID = "a2".repeat(32)
        val SENDER = "cc".repeat(32)

        /** A received photo with no remote locator, so only cached plaintext makes it forwardable. */
        val LOCATORLESS_PHOTO =
            MediaAttachmentReferenceFfi(
                locators = emptyList(),
                ciphertextSha256 = "e1".repeat(32),
                plaintextSha256 = "e2".repeat(32),
                nonceHex = "e3".repeat(12),
                fileName = "photo.png",
                mediaType = "image/png",
                version = EncryptedMediaVersionFfi.V1,
                sourceEpoch = 1uL,
                dim = null,
                thumbhash = null,
            )
    }
}
