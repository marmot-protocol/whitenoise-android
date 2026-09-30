package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleColumnTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/** Real route coverage: the composer callback must snap, not animate, an accepted send from history. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationAcceptedSendRouteScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var controller: ConversationController? = null
    private var publishCount = 0
    private val originalTimeZone = TimeZone.getDefault()

    /** Releases every conversation-owned job and restores process-wide timestamp formatting. */
    @After
    fun releaseFixture() {
        controller?.onCleared()
        TimeZone.setDefault(originalTimeZone)
    }

    /** Exercises the production Send button and measures the pending bubble above the composer. */
    @Test
    fun acceptedSendFromFarHistorySnapsToThePendingTail() {
        val conversation = createConversation()
        val appState = conversation.appState
        val evidence = SendRouteEvidence()
        mountHistoryConversation(conversation, evidence)
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { evidence.viewport?.mode is ConversationScrollMode.ReadingHistory }
        assertTrue(checkNotNull(evidence.viewport).canScrollBackward)
        evidence.writes.clear()
        composeRule.mainClock.autoAdvance = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        awaitConversationCondition { publishCount == 1 }
        repeat(30) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(SENT_TEXT).assertIsDisplayed()
        val sentId =
            conversation.timeline
                .single { it.record.plaintext == SENT_TEXT }
                .record.messageIdHex
        val bubbleBounds =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(sentId), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val composerBounds = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the complete pending bubble clears the composer", bubbleBounds.bottom <= composerBounds.top)
        assertEquals(1, publishCount)
        assertTrue(evidence.writes.isNotEmpty())
        assertTrue("accepted sends never animate across history", evidence.writes.none { it.animated })
        assertFalse("the newest edge is physically reached", checkNotNull(evidence.viewport).canScrollBackward)
        assertTrue(checkNotNull(evidence.viewport).mode is ConversationScrollMode.FollowingTail)
        assertEquals(
            "acceptance clears only the origin draft",
            null,
            appState.draftStore.get(ConversationTimelineTestIds.ACCOUNT_REF, ConversationTimelineTestIds.GROUP_ID),
        )
        composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_accepted_send_history_tail.png")
    }

    /** Builds one synthetic authoritative window with typed durable acceptance and no relay publication. */
    private fun createConversation(): ConversationController {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val records =
            List(60) { index ->
                timelineRecord((index + 1).toString(16).padStart(64, '0'), (index + 1).toULong(), "History $index")
            }
        val subscriptions =
            ScriptedConversationLiveSubscriptions(
                listOf(ScriptedConversationTimelineSubscription(timelinePage(*records.toTypedArray()))),
                conversationTimelineTestGroup(),
            )
        val appState = conversationTimelineTestAppState(subscriptions.subscriptions)
        appState.draftStore.set(
            ConversationTimelineTestIds.ACCOUNT_REF,
            ConversationTimelineTestIds.GROUP_ID,
            TextFieldValue(SENT_TEXT),
        )
        val conversation =
            ConversationController(
                appState = appState,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
                markdownParser = { EMPTY_MARKDOWN_DOCUMENT },
                clockMillis = { 1_672_531_260_000L },
                textPublisher = { _, _, _, _ ->
                    publishCount++
                    SendSummaryFfi(
                        published = 0u,
                        messageIds = listOf("c3".repeat(32)),
                        acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                        maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                    )
                },
            )
        controller = conversation
        awaitConversationCondition { conversation.timeline.size == records.size }
        return conversation
    }

    /** Mounts the real route with a logical far-history bookmark and the actual production scroll writer. */
    private fun mountHistoryConversation(
        conversation: ConversationController,
        evidence: SendRouteEvidence,
    ) {
        val historyId = 11.toString(16).padStart(64, '0')
        composeRule.setContent {
            CompositionLocalProvider(LocalConversationScrollEvidenceSink provides evidence) {
                WhiteNoiseTheme {
                    ConversationScreen(
                        appState = conversation.appState,
                        chat =
                            ChatListItem(
                                group = conversationTimelineTestGroup(),
                                latest = null,
                                otherMemberAccount = null,
                                memberCount = 1,
                                memberSnapshot = conversationTimelineMemberSnapshot(),
                            ),
                        controller = conversation,
                        onBack = {},
                        restoredScrollSnapshot =
                            ConversationScrollSnapshot(
                                firstVisibleItemIndex = 49,
                                firstVisibleItemScrollOffset = 0,
                                anchorItemId = "msg:$historyId",
                                anchorMessageIdHex = historyId,
                            ),
                    )
                }
            }
        }
    }

    /** Keeps identifiers private in test memory while observing the real writer and measured viewport. */
    private class SendRouteEvidence : ConversationScrollEvidenceSink {
        val writes = CopyOnWriteArrayList<ConversationScrollWriteEvidence>()
        var viewport: ConversationViewportEvidence? = null

        /** Retains the measured current frame for bubble/composer clearance assertions. */
        override fun onViewport(snapshot: ConversationViewportEvidence) {
            viewport = snapshot
        }

        /** Records every real list write so an animated history glide cannot pass as a snap. */
        override fun onWrite(write: ConversationScrollWriteEvidence) {
            writes += write
        }
    }

    private companion object {
        const val SENT_TEXT = "Accepted send from older history"
    }
}
