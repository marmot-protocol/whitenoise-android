package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.R
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

/** Exercises Read More on the real route in history and at the newest edge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationInlineReadMoreScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var controller: ConversationController? = null
    private var publishCount = 0
    private val originalTimeZone = TimeZone.getDefault()

    /** Releases the conversation and restores the process-wide time zone after each test. */
    @After
    fun releaseFixture() {
        controller?.onCleared()
        TimeZone.setDefault(originalTimeZone)
    }

    /**
     * Expanding a long history message keeps the existing composer mounted. Sending from it still reveals the
     * accepted outgoing message above that composer.
     */
    @Test
    fun acceptedSendAfterInlineReadMoreRevealsThePendingTail() {
        val conversation = createConversation(longMessageAtTail = false)
        val evidence = SendRouteEvidence()
        composeRule.mountSendRouteConversation(conversation, evidence)
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { evidence.viewport?.mode is ConversationScrollMode.ReadingHistory }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val readMore = visibleReadMoreButton(context)
        readMore.performClick()
        // Allow the expanded row to remeasure and the scroll coordinator to place it.
        repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).assertIsDisplayed()
        readMore.assertDoesNotExist()
        assertFirstNewLineIsVisible(evidence)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_read_more_inline.png")
        evidence.writes.clear()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        awaitConversationCondition { publishCount == 1 }
        repeat(30) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.waitForIdle()

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
        assertTrue("the pending bubble clears the conversation composer", bubbleBounds.bottom <= composerBounds.top)
        assertEquals("the composer publishes exactly once", 1, publishCount)
        assertTrue("accepted sends never animate across history", evidence.writes.none { it.animated })
        assertTrue(checkNotNull(evidence.viewport).mode is ConversationScrollMode.FollowingTail)
    }

    /** Expanding the newest bubble keeps following the chat if its new end remains at the natural tail. */
    @Test
    fun smallExpansionAtNaturalTailKeepsFollowingNewest() {
        val conversation = createConversation(longMessageAtTail = true)
        val evidence = SendRouteEvidence()
        composeRule.mountSendRouteConversation(conversation, evidence, liveTail = true)
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) {
            val viewport = evidence.viewport
            viewport?.mode is ConversationScrollMode.FollowingTail && !viewport.canScrollBackward
        }

        val readMore = visibleReadMoreButton(ApplicationProvider.getApplicationContext())
        readMore.performClick()
        repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
        readMore.assertDoesNotExist()
        val viewport = checkNotNull(evidence.viewport)
        assertFalse("the newest edge remains physically reached", viewport.canScrollBackward)
        assertTrue(
            "incoming messages can still follow the tail",
            viewport.mode is ConversationScrollMode.FollowingTail,
        )
        composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_read_more_at_tail.png")
    }

    /** Finds the interactive Read More footer, excluding the zero-size measurement copy. */
    private fun visibleReadMoreButton(context: Context) =
        composeRule.onNode(
            hasText(context.getString(R.string.message_read_more)) and
                SemanticsMatcher("visible footer action") { it.boundsInRoot.width > 0f },
        )

    /** Checks actual line geometry because an oversized text node can be visible while its continuation is clipped. */
    private fun assertFirstNewLineIsVisible(evidence: SendRouteEvidence) {
        val body = composeRule.onNodeWithText("Long history line 0", substring = true, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val continuation = layout.layoutInput.text.text.indexOf("Long history line 52")
        assertTrue("the expanded layout contains the first line past the preview", continuation >= 0)
        val line = layout.getLineForOffset(continuation)
        val node = body.fetchSemanticsNode()
        val top = node.positionInRoot.y + layout.getLineTop(line)
        val bottom = node.positionInRoot.y + layout.getLineBottom(line)
        val composerTop = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "first new line at $top..$bottom, visible body from ${node.boundsInRoot.top} to composer $composerTop, " +
                "scroll writes: ${evidence.writes}",
            top >= node.boundsInRoot.top && bottom <= composerTop,
        )
    }

    /** Seeds one long body at the history anchor or a smaller expandable body at the newest edge. */
    private fun createConversation(longMessageAtTail: Boolean): ConversationController {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val records =
            List(60) { index ->
                val text =
                    when {
                        !longMessageAtTail && index == SEND_ROUTE_HISTORY_ANCHOR_INDEX ->
                            List(120) { line -> "Long history line $line" }.joinToString("\n")
                        longMessageAtTail && index == 59 ->
                            List(55) { line -> "Long tail line $line" }.joinToString("\n")
                        else -> "History $index"
                    }
                timelineRecord((index + 1).toString(16).padStart(64, '0'), (index + 1).toULong(), text)
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
                markdownParser = { sendRoutePlainMarkdownDocument(it) },
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

    private companion object {
        const val SENT_TEXT = "Accepted send from older history"
    }
}
