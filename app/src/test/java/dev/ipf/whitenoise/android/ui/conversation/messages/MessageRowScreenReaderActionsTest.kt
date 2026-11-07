package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reads the production bubble's merged row node the way TalkBack does: one stop carrying the row's actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class MessageRowScreenReaderActionsTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun incomingReactedRowIsOneStopOfferingItsActions() {
        val surface = swipeTestSurface(context, reacted = true, mine = false, media = false)
        composeRule.setContent { SwipeTestBubbleHost(surface) }

        val row = rowConfig()
        assertTrue(row.isMergingSemanticsOfDescendants)
        val labels = actionLabels(row)
        val expected =
            listOf(R.string.reply, R.string.message_react, R.string.copy, R.string.forward, R.string.delete)
                .plus(listOf(R.string.info, R.string.chat_list_search_open_profile))
                .plus(R.string.view_reactors)
                .map(context::getString)
        assertTrue("missing ${expected - labels.toSet()} in $labels", labels.containsAll(expected))
        // Actions that do not apply to this row are left out rather than offered and ignored.
        listOf(R.string.retry, R.string.discard_failed_message, R.string.go_to_message, R.string.edit_history)
            .map(context::getString)
            .forEach { assertFalse("$it offered in $labels", it in labels) }
    }

    @Test
    fun replyActionStartsAReplyToThisMessage() {
        val surface = swipeTestSurface(context, reacted = false, mine = false, media = false)
        composeRule.setContent { SwipeTestBubbleHost(surface) }

        val reply = context.getString(R.string.reply)
        composeRule.runOnIdle {
            rowConfig()[SemanticsActions.CustomActions].single { it.label == reply }.action()
        }

        composeRule.runOnIdle { assertEquals(SWIPE_TEST_MESSAGE_ID, surface.controller.replyingTo?.messageIdHex) }
    }

    @Test
    fun failedOwnSendLeadsWithRetryAndDiscard() {
        val sent = swipeTestSurface(context, reacted = false, mine = true, media = false)
        val surface = sent.copy(item = sent.item.copy(status = MessageStatus.Failed))
        composeRule.setContent { SwipeTestBubbleHost(surface) }

        assertEquals(
            listOf(R.string.retry, R.string.discard_failed_message).map(context::getString),
            actionLabels(rowConfig()).take(2),
        )
    }

    @Test
    fun batchSelectionLeavesTheRowToTheSelectionTarget() {
        val surface = swipeTestSurface(context, reacted = true, mine = false, media = false)
        composeRule.setContent { SwipeTestBubbleHost(surface, selecting = true) }

        val row = rowConfig()
        assertFalse(row.isMergingSemanticsOfDescendants)
        assertNull(row.getOrNull(SemanticsActions.CustomActions))
    }

    private fun rowConfig(): SemanticsConfiguration =
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(SWIPE_TEST_MESSAGE_ID), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config

    private fun actionLabels(config: SemanticsConfiguration): List<String> = config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
}
