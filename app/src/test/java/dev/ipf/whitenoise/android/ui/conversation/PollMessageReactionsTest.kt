package dev.ipf.whitenoise.android.ui.conversation

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_ACTION_REACTION_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.conversation.reactions.REACTION_PILL_TEST_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Poll discussion shares native reaction admission, rollback and event-scoped removal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class PollMessageReactionsTest : PollMessageTestFixtures() {
    @get:Rule val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())
    private val menuOpen = mutableStateOf(false)

    @Before fun bindRoster() = runTest { pollController.retryMembers() }

    @After fun clearController() {
        pollController.onCleared()
    }

    @Test fun closedPollQuickReactionDetailsAndRemovalPreserveVotes() {
        val item = render()
        openMenu(item.record.messageIdHex)
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        advanceReactionQuietPeriod()
        composeRule.waitUntil { recordedCalls().any { it.first == "reactToMessage" } }
        composeRule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").performClick()
        composeRule.onNodeWithText("Tap to remove").performClick()
        advanceReactionQuietPeriod()
        composeRule.waitUntil { recordedCalls().any { it.first == "deleteMessage" } }
        composeRule.runOnIdle {
            assertEquals(
                listOf("personal", item.record.groupIdHex, item.record.messageIdHex, "👍"),
                recordedCalls().first { it.first == "reactToMessage" }.second.take(4),
            )
            assertEquals(
                listOf("personal", item.record.groupIdHex, "aa".repeat(32)),
                recordedCalls().first { it.first == "deleteMessage" }.second.take(3),
            )
            assertTrue(recordedCalls().none { it.first == "castPollVote" })
            assertEquals(emptyList<String>(), item.projected?.poll?.localSelection)
            assertEquals(0uL, item.projected?.poll?.participants)
        }
    }

    @Test fun fullPickerReactionKeepsOriginalTargetAndDoesNotVote() {
        val item = render()
        openMenu(item.record.messageIdHex)
        composeRule.onNodeWithContentDescription("Open emoji picker").performClick()
        // The real picker loads its catalog on IO; Compose idleness alone does not await that work.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("😀").performClick()
        advanceReactionQuietPeriod()
        composeRule.waitUntil { recordedCalls().any { it.first == "reactToMessage" } }
        assertEquals(
            listOf("personal", item.record.groupIdHex, item.record.messageIdHex, "😀"),
            recordedCalls().first { it.first == "reactToMessage" }.second.take(4),
        )
        assertTrue(recordedCalls().none { it.first == "castPollVote" })
    }

    @Test fun removedTargetCannotReceiveQueuedQuickReaction() {
        val item = render()
        openMenu(item.record.messageIdHex)
        composeRule.runOnIdle { pollController.timelineItemsById.remove(item.record.messageIdHex) }
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        composeRule.runOnIdle { assertTrue(recordedCalls().none { it.first == "reactToMessage" }) }
    }

    @Test fun nativeFailureRollsBackOptimisticPollReaction() {
        val item = render()
        reactionFailure = IllegalStateException("rejected fixture reaction")
        openMenu(item.record.messageIdHex)
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        advanceReactionQuietPeriod()
        composeRule.waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            recordedCalls().any { it.first == "reactToMessage" } &&
                pollController.reactions[item.record.messageIdHex].isNullOrEmpty()
        }
        composeRule.runOnIdle { assertTrue(recordedCalls().none { it.first == "castPollVote" }) }
    }

    @Test fun removedPollDuringQuietPeriodDiscardsQueuedReaction() {
        val item = render()
        openMenu(item.record.messageIdHex)
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        composeRule.runOnIdle {
            assertTrue(pollController.reactions[item.record.messageIdHex].orEmpty().any { it.mine })
            pollController.timelineItemsById.remove(item.record.messageIdHex)
        }
        assertQueuedReactionDiscarded(item.record.messageIdHex)
    }

    @Test fun expiredPollDuringQuietPeriodDiscardsQueuedReaction() {
        val item = render()
        openMenu(item.record.messageIdHex)
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        composeRule.runOnIdle {
            assertTrue(pollController.reactions[item.record.messageIdHex].orEmpty().any { it.mine })
            retain(item.copy(record = item.record.copy(retentionExpiresAt = 1uL)))
        }
        assertQueuedReactionDiscarded(item.record.messageIdHex)
    }

    @Test fun queuedRemovalRechecksAvailabilityBeforeDeletingReactionEvent() =
        runTest {
            val item = render()
            pollController.toggleReaction("👍", item.record)
            var available = true
            var admissionChecks = 0
            val removal =
                async(start = CoroutineStart.UNDISPATCHED) {
                    pollController.toggleReaction("👍", item.record) {
                        admissionChecks++
                        available
                    }
                }
            available = false
            removal.await()
            assertEquals(1, admissionChecks)
            assertEquals(1, recordedCalls().count { it.first == "reactToMessage" })
            assertTrue(recordedCalls().none { it.first == "deleteMessage" || it.first == "unreactFromMessage" })
        }

    @Test fun pollRemovedDuringGroupLockWaitDoesNotReachNative() =
        runTest {
            val item = render()
            val owner = PollMessageActionOwner("personal", item.record.groupIdHex, item.record.messageIdHex)
            val release = CompletableDeferred<Unit>()
            val lockHolder =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    pollState.withGroupCommitLock("personal", item.record.groupIdHex) { release.await() }
                }
            val reaction =
                async(start = CoroutineStart.UNDISPATCHED) {
                    pollController.toggleReaction("👍", item.record) {
                        currentPollActionTarget(pollController, owner) != null
                    }
                }
            advanceTimeBy(200L)
            runCurrent()
            assertTrue(pollController.reactions[item.record.messageIdHex].orEmpty().any { it.mine })
            pollController.timelineItemsById.remove(item.record.messageIdHex)
            release.complete(Unit)
            lockHolder.join()
            reaction.await()
            assertTrue(pollController.reactions[item.record.messageIdHex].isNullOrEmpty())
            assertTrue(recordedCalls().none { it.first == "reactToMessage" })
        }

    private fun assertQueuedReactionDiscarded(target: String) {
        advanceReactionQuietPeriod()
        composeRule.waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            pollController.reactions[target].isNullOrEmpty()
        }
        assertTrue(recordedCalls().none { it.first == "reactToMessage" || it.first == "unreactFromMessage" })
    }

    @Test fun pollReplyUsesExistingNativeComposerSendAndStableTarget() =
        runTest {
            val item = render()
            openMenu(item.record.messageIdHex)
            composeRule.onNodeWithText("Reply").performClick()
            pollController.send("I prefer soup")
            val call = recordedCalls().first { it.first == "replyToMessageWithClientToken" }.second
            assertEquals(
                listOf("personal", item.record.groupIdHex, item.record.messageIdHex, "I prefer soup"),
                call.take(4),
            )
            assertTrue(recordedCalls().none { it.first == "castPollVote" })
        }

    /** App mutations use Android Main, whose delayed reaction admission needs its paused looper advanced. */
    private fun advanceReactionQuietPeriod() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200L))
    }

    private fun openMenu(id: String) {
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(id))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
    }

    private fun render() =
        pollMessage(closed = true).also { item ->
            retain(item)
            composeRule.setContent {
                WhiteNoiseTheme {
                    Surface(Modifier.fillMaxWidth()) { RealPollMessage(item, menuOpen.value) { menuOpen.value = it } }
                }
            }
        }
}
