package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_PICKER_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_DETAILS_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Poll controls and ordinary message gestures share one production hit surface. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class PollMessageActionsTest : PollMessageTestFixtures() {
    @get:Rule val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())
    private val menuOpen = mutableStateOf(false)
    private val mounted = mutableStateOf(true)

    @Before fun bindRoster() = runTest { pollController.retryMembers() }

    @After fun clearController() {
        pollController.onCleared()
    }

    @Test fun longPressAnOptionOpensReplyWithoutVoting() {
        val item = render()
        composeRule.onNodeWithText("Soup").performTouchInput { longClick() }
        composeRule.onNodeWithText("Reply").performClick()
        composeRule.runOnIdle {
            assertEquals(item.record.messageIdHex, pollController.replyingTo?.messageIdHex)
            assertTrue(recordedCalls().none { it.first == "castPollVote" })
        }
    }

    @Test fun closedPollStillHasAccessibleReplyAndSafeEnvelopeCopy() {
        val item = render(closed = true)
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(item.record.messageIdHex))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        composeRule.onNodeWithContentDescription("Poll", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("private-envelope", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Reply").performClick()
        composeRule.onNodeWithText("Poll closed").assertExists()
        composeRule.onNodeWithText("private-envelope", substring = true).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(item.record.messageIdHex, pollController.replyingTo?.messageIdHex) }
    }

    @Test fun swipeRepliesWithoutCastingVote() {
        val item = render()
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(item.record.messageIdHex))
            .performTouchInput { swipeRight() }
        composeRule.runOnIdle {
            assertEquals(item.record.messageIdHex, pollController.replyingTo?.messageIdHex)
            assertTrue(recordedCalls().none { it.first == "castPollVote" })
        }
    }

    @Test fun pollDetailsAndUnsupportedActionsNeverShowTheEnvelope() {
        val item = render(closed = true)
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(item.record.messageIdHex))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        composeRule.onNodeWithText("Forward").assertDoesNotExist()
        composeRule.onNodeWithText("Speak aloud").assertDoesNotExist()
        composeRule.onNodeWithText("Info").performClick()
        composeRule.onNodeWithTag(MESSAGE_DETAILS_TAG).assertExists()
        composeRule.onNodeWithText("private-envelope", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Poll").assertExists()
    }

    @Test fun optionTapUsesNativePollTargetAndDoesNotReply() {
        val item = render()
        composeRule.onNodeWithText("Soup").performClick()
        composeRule.waitUntil { synchronized(nativeCalls) { nativeCalls.any { it.first == "castPollVote" } } }
        composeRule.runOnIdle {
            val call = recordedCalls().first { it.first == "castPollVote" }.second
            assertEquals(
                listOf("personal", item.record.groupIdHex, item.record.messageIdHex, listOf("a")),
                call.take(4),
            )
            assertEquals(null, pollController.replyingTo)
            assertEquals(false, menuOpen.value)
        }
    }

    @Test fun verticalDragOfAnOptionDoesNotVoteOrReply() {
        render()
        // The label's own height is smaller than touch slop. Drag into the real scroll owner.
        composeRule.onNodeWithText("Soup").performTouchInput { swipe(center, center - Offset(0f, 80f)) }
        composeRule.runOnIdle {
            assertNull(pollController.replyingTo)
            assertTrue(recordedCalls().none { it.first == "castPollVote" })
        }
    }

    @Test fun nativePendingPollKeepsVotingAvailable() {
        val item = render(pending = true)
        composeRule.onNodeWithText("Soup").performClick()
        composeRule.waitUntil { recordedCalls().any { it.first == "castPollVote" } }
        val call = recordedCalls().first { it.first == "castPollVote" }.second
        assertEquals(
            listOf("personal", item.record.groupIdHex, item.record.messageIdHex, listOf("a")),
            call.take(4),
        )
        assertNull(pollController.replyingTo)
    }

    @Test fun unavailablePollDiscussionKeepsLocalDeleteAvailable() {
        val item = render(failed = true)
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(item.record.messageIdHex))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        composeRule.onNodeWithText("Reply").assertDoesNotExist()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete for me").assertExists()
        assertTrue(recordedCalls().none { it.first == "reactToMessage" || it.first == "castPollVote" })
    }

    @Test fun accessibleFullPickerClosesWhenConversationBecomesReadOnly() {
        val item = render()
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(item.record.messageIdHex))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        composeRule.onNodeWithContentDescription("Open emoji picker").performClick()
        composeRule.onNodeWithTag(EMOJI_PICKER_TEST_TAG).assertExists()
        composeRule.runOnIdle { pollController.applyGroupStateForTest(pollController.group.copy(disbanded = true)) }
        composeRule.onNodeWithTag(EMOJI_PICKER_TEST_TAG).assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(recordedCalls().none { it.first == "reactToMessage" || it.first == "castPollVote" })
        }
    }

    @Test fun replyCallbackRechecksTargetRemovedWhileMenuOpen() {
        val item = render()
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(item.record.messageIdHex))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        composeRule.runOnIdle { pollController.removeProjectedRecord(item.record.messageIdHex) }
        composeRule.onNodeWithText("Reply").performClick()
        composeRule.runOnIdle { assertNull(pollController.replyingTo) }
    }

    @Test fun disposingRowClosesItsMenu() {
        val item = render()
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(item.record.messageIdHex))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        composeRule.onNodeWithText("Reply").assertExists()
        composeRule.runOnIdle { mounted.value = false }
        composeRule.onNodeWithText("Reply").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(false, menuOpen.value) }
    }

    @Test fun disposedControllerStillSettlesTheMountedCardsVoteProgress() {
        beforeVoteReturn = { pollController.onCleared() }
        render()
        composeRule.onNodeWithText("Soup").performClick()
        composeRule.waitUntil {
            !pollController.acceptsConversationActionOwner("personal", pollController.group.groupIdHex)
        }
        composeRule.waitUntil { composeRule.onAllNodesWithText("Sending").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithText("Soup").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, recordedCalls().count { it.first == "castPollVote" }) }
    }

    @Test fun newlyMountedReadOnlyPollDoesNotDismissAnotherRowsMenu() {
        val first = pollMessage()
        val second =
            first.copy(
                id = "msg:${"cc".repeat(32)}",
                record = first.record.copy(messageIdHex = "cc".repeat(32)),
                status = MessageStatus.Failed,
            )
        val secondMounted = mutableStateOf(false)
        retain(first)
        retain(second)
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    RealPollMessage(first, menuOpen.value) { menuOpen.value = it }
                    if (secondMounted.value) RealPollMessage(second, false) { menuOpen.value = it }
                }
            }
        }
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(first.record.messageIdHex))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        composeRule.onNodeWithText("Reply").assertExists()
        composeRule.runOnIdle { secondMounted.value = true }
        composeRule.onNodeWithText("Reply").assertExists()
        composeRule.runOnIdle { assertTrue(menuOpen.value) }
    }

    @Test fun deliveryWarningPreservesPollContentWithoutPermittingVotes() {
        render(invalidation = "local_publish_failed")
        composeRule.onNodeWithText("Lunch?").assertExists()
        composeRule.onNodeWithText("Soup").assertIsNotEnabled()
        composeRule.onNodeWithTag("poll-message-warning").assertExists()
        composeRule.runOnIdle { assertTrue(recordedCalls().none { it.first == "castPollVote" }) }
    }

    private fun render(
        closed: Boolean = false,
        pending: Boolean = false,
        failed: Boolean = false,
        invalidation: String? = null,
    ) = pollMessage(
        closed,
        mine = pending || failed,
        status =
            when {
                failed -> MessageStatus.Failed
                pending -> MessageStatus.Pending
                else -> MessageStatus.Received
            },
    ).let { item ->
        item.copy(projected = checkNotNull(item.projected).copy(invalidationStatus = invalidation))
    }.also { item ->
        retain(item)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.fillMaxWidth()) {
                    LazyColumn {
                        item { if (mounted.value) RealPollMessage(item, menuOpen.value) { menuOpen.value = it } }
                        item { Spacer(Modifier.height(1_000.dp)) }
                    }
                }
            }
        }
    }
}
