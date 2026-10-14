package dev.ipf.whitenoise.android.ui.conversation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
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

/** What whole-message Copy puts on the clipboard for the fixture's "Lunch?" poll. */
private const val LUNCH_POLL_COPY = "Poll: Lunch?\n1. Soup\n2. Salad"

/** A clipboard seed that only an unchanged clipboard still holds. */
private const val UNTOUCHED_CLIPBOARD = "untouched"

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

    /** Copy puts the question and numbered options on the clipboard instead of the bare Poll label. */
    @Test fun copyPutsTheQuestionAndOptionsOnTheClipboard() {
        val item = render()
        openMenu(item)
        composeRule.onNodeWithText("Copy").performClick()
        composeRule.runOnIdle {
            assertEquals(LUNCH_POLL_COPY, clipboardText())
            assertEquals(false, menuOpen.value)
        }
    }

    /** Closed voting leaves the poll's content in place, so Copy still copies it. */
    @Test fun closedPollStillCopiesItsContent() {
        val item = render(closed = true)
        openMenu(item)
        composeRule.onNodeWithText("Copy").performClick()
        composeRule.runOnIdle { assertEquals(LUNCH_POLL_COPY, clipboardText()) }
    }

    /** A delivery warning keeps the question and choices visible, so Copy copies them even though voting is off. */
    @Test fun deliveryWarningPollStillCopiesItsContent() {
        val item = render(invalidation = "local_publish_failed")
        openMenu(item)
        composeRule.onNodeWithText("Copy").performClick()
        composeRule.runOnIdle { assertEquals(LUNCH_POLL_COPY, clipboardText()) }
    }

    /** A persisted failure replaces the poll with a notice, so there is nothing to copy and Copy is not offered. */
    @Test fun failedPollOffersNoCopy() {
        val item = render(invalidation = "UnknownEngineReason")
        openMenu(item)
        composeRule.onNodeWithText("Delete").assertExists()
        composeRule.onNodeWithText("Copy").assertDoesNotExist()
    }

    /** Copy rechecks the poll when chosen, so one removed while the menu was open leaves the clipboard alone. */
    @Test fun copyRechecksPollRemovedWhileMenuOpen() {
        setClipboardText(UNTOUCHED_CLIPBOARD)
        val item = render()
        openMenu(item)
        composeRule.runOnIdle { pollController.removeProjectedRecord(item.record.messageIdHex) }
        composeRule.onNodeWithText("Copy").performClick()
        composeRule.runOnIdle { assertEquals(UNTOUCHED_CLIPBOARD, clipboardText()) }
    }

    /** Copy text belongs to the poll's account and chat, and stops once its conversation is torn down. */
    @Test fun copyTextIsOwnedByItsAccountAndChat() {
        val item = pollMessage()
        retain(item)
        val owner = PollMessageActionOwner("personal", pollController.group.groupIdHex, item.record.messageIdHex)

        assertEquals(LUNCH_POLL_COPY, currentPollCopyText(pollController, owner, MessageTextCopy.Default))
        assertNull(currentPollCopyText(pollController, owner.copy(accountRef = "other"), MessageTextCopy.Default))
        assertNull(currentPollCopyText(pollController, owner.copy(groupId = "dd".repeat(32)), MessageTextCopy.Default))
        pollController.onCleared()
        assertNull(currentPollCopyText(pollController, owner, MessageTextCopy.Default))
    }

    /** Deleted and expired polls copy nothing, so their content never outlives them on the clipboard. */
    @Test fun deletedAndExpiredPollsCopyNothing() {
        val source = pollMessage()
        val deleted =
            source.copy(
                id = "msg:${"cc".repeat(32)}",
                record = source.record.copy(messageIdHex = "cc".repeat(32)),
                projected = checkNotNull(source.projected).copy(messageIdHex = "cc".repeat(32), deleted = true),
            )
        val expired =
            source.copy(
                id = "msg:${"ee".repeat(32)}",
                record = source.record.copy(messageIdHex = "ee".repeat(32), retentionExpiresAt = 1uL),
                projected = checkNotNull(source.projected).copy(messageIdHex = "ee".repeat(32)),
            )
        listOf(deleted, expired).forEach { item ->
            retain(item)
            val owner = PollMessageActionOwner("personal", pollController.group.groupIdHex, item.record.messageIdHex)
            assertNull(currentPollCopyText(pollController, owner, MessageTextCopy.Default))
        }
    }

    /** Opens the poll's action menu through the same long-click accessibility action TalkBack uses. */
    private fun openMenu(item: TimelineMessage) {
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(item.record.messageIdHex))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
    }

    /** The system clipboard's current plain text, as another app or chat would paste it. */
    private fun clipboardText(): String? =
        systemClipboard()
            .primaryClip
            ?.getItemAt(0)
            ?.text
            ?.toString()

    /** Seeds the system clipboard so a test can prove an action left it unchanged. */
    private fun setClipboardText(text: String) {
        systemClipboard().setPrimaryClip(ClipData.newPlainText("test", text))
    }

    /** The real Android clipboard Compose writes through. */
    private fun systemClipboard(): ClipboardManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.getSystemService(ClipboardManager::class.java)
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
