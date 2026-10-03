package dev.ipf.whitenoise.android.ui.conversation

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.ipf.marmotkit.GroupSystemEventProvenanceFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_ACTION_REACTION_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.reactions.REACTION_PILL_TEST_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Exercises the real timeline branch and shared native reaction mutation with a bounded fake transport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSystemReactionsTest : GroupSystemReactionTestFixtures() {
    @get:Rule val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())
    private val menuOpen = mutableStateOf(false)
    private val readOnly = mutableStateOf(false)
    private val mountedItem = mutableStateOf<TimelineMessage?>(null)

    /** Native roster admission is identical to ordinary message reactions. */
    @Before fun bindRoster() = runTest { pollController.retryMembers() }

    /** Releases controller-owned coroutines after every test. */
    @After fun clearController() {
        pollController.onCleared()
    }

    /** Semantic long press exposes only the activity's supported menu actions. */
    @Test fun accessibleMenuAndChipToggleUseNativeReactionTarget() {
        val item = render()
        openMenu()
        composeRule.onNodeWithText("Delete for me").assertExists()
        listOf("Reply", "Edit", "Copy", "Forward", "Share", "Save", "Info", "Delete for everyone").forEach {
            composeRule.onNodeWithText(it).assertDoesNotExist()
        }
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        settle()
        composeRule.waitUntil { recordedCalls().any { it.first == "reactToMessage" } }
        composeRule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").assertIsSelected().performClick()
        settle()
        composeRule.waitUntil { recordedCalls().any { it.first == "deleteMessage" } }
        assertEquals(
            listOf("personal", item.record.groupIdHex, item.record.messageIdHex, "👍"),
            recordedCalls().first { it.first == "reactToMessage" }.second.take(4),
        )
        assertEquals("aa".repeat(32), recordedCalls().first { it.first == "deleteMessage" }.second[2])
    }

    /** Full emoji selection retains the original native system-row identifier. */
    @Test fun fullPickerUsesOriginalActivityTarget() {
        val item = render()
        openMenu()
        composeRule.onNodeWithContentDescription("Open emoji picker").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().size == 1 }
        composeRule.onNodeWithText("😀").performClick()
        settle()
        composeRule.waitUntil { recordedCalls().any { it.first == "reactToMessage" } }
        assertEquals(item.record.messageIdHex, recordedCalls().first { it.first == "reactToMessage" }.second[2])
    }

    /** A rejected native operation removes the optimistic pill. */
    @Test fun failureRollsBackOptimisticReaction() {
        val item = render()
        reactionFailure = IllegalStateException("rejected activity reaction")
        openMenu()
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        settle()
        composeRule.waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            recordedCalls().any { it.first == "reactToMessage" } &&
                pollController.reactions[item.record.messageIdHex].isNullOrEmpty()
        }
    }

    /** Rapid add/remove intent settles without publishing either mutation. */
    @Test fun duplicateTapsConvergeWithoutPublishing() {
        val item = render()
        openMenu()
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        composeRule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").performClick()
        settle()
        composeRule.waitUntil { pollController.reactions[item.record.messageIdHex].isNullOrEmpty() }
        assertTrue(recordedCalls().none { it.first == "reactToMessage" || it.first == "deleteMessage" })
    }

    /** Removing the target while the reaction waits must prevent native admission. */
    @Test fun removedTargetDiscardsQueuedReaction() {
        val item = render()
        openMenu()
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        composeRule.runOnIdle { pollController.removeProjectedRecord(item.record.messageIdHex) }
        settle()
        composeRule.waitUntil { pollController.reactions[item.record.messageIdHex].isNullOrEmpty() }
        assertTrue(recordedCalls().none { it.first == "reactToMessage" })
    }

    /** Read-only history retains reactor details without a removal or quick-reaction action. */
    @Test fun readOnlyHistoryKeepsDetailsWithoutMutation() {
        render()
        openMenu()
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        settle()
        composeRule.waitUntil { recordedCalls().any { it.first == "reactToMessage" } }
        composeRule.runOnIdle { readOnly.value = true }
        composeRule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").performClick()
        composeRule.onNodeWithText("Tap to remove").assertDoesNotExist()
        assertEquals(1, recordedCalls().count { it.first == "reactToMessage" })
        assertTrue(recordedCalls().none { it.first == "deleteMessage" })
    }

    /** Long press keeps the ordinary reactor sheet and its own-reaction removal behavior. */
    @Test fun chipLongPressOpensDetailsAndRemovesOwnReaction() {
        render()
        openMenu()
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        settle()
        composeRule.waitUntil { recordedCalls().any { it.first == "reactToMessage" } }
        composeRule
            .onNodeWithTag("$REACTION_PILL_TEST_TAG:0")
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        composeRule.onNodeWithText("Tap to remove").performClick()
        composeRule.onNodeWithText("Tap to remove").assertDoesNotExist()
        assertTrue(recordedCalls().none { it.first == "deleteMessage" })
        settle()
        composeRule.waitUntil { recordedCalls().any { it.first == "deleteMessage" } }
    }

    /** Deleted and invalidated projections remove the reaction hit targets immediately. */
    @Test fun deletedAndInvalidatedActivityHaveNoReactionChips() {
        val item = render()
        openMenu()
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        settle()
        composeRule.runOnIdle { mountedItem.value = item.copy(projected = item.projected!!.copy(deleted = true)) }
        composeRule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").assertDoesNotExist()
        composeRule.runOnIdle {
            mountedItem.value = item.copy(projected = item.projected!!.copy(invalidationStatus = "BeyondAppRetention"))
        }
        composeRule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").assertDoesNotExist()
    }

    /** Navigating away retires the controller before its queued intent can reach native code. */
    @Test fun closedControllerDiscardsQueuedReaction() {
        render()
        openMenu()
        composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍").performClick()
        composeRule.runOnIdle { pollController.onCleared() }
        settle()
        assertTrue(recordedCalls().none { it.first == "reactToMessage" })
    }

    /** Invite previews never acquire a writable activity target even with an authenticated projection. */
    @Test fun pendingConfirmationCannotReact() {
        val item = activity()
        val pending =
            ConversationController(
                appState = pollState,
                initialGroup = group().copy(pendingConfirmation = true),
                initialMemberSnapshot = memberSnapshot(),
            )
        try {
            pending.retainTimelineItemForTest(item)
            assertTrue(pending.group.pendingConfirmation)
            val owner = GroupSystemReactionOwner("personal", item.record.groupIdHex, item.record.messageIdHex)
            assertNull(currentGroupSystemReactionTarget(pending, owner))
        } finally {
            pending.onCleared()
        }
    }

    /** Native actor/provenance/deletion gates reject unsafe or no-longer-addressable activity rows. */
    @Test fun invalidActivityAndStaleOwnersCannotReact() {
        val item = activity()
        val owner = GroupSystemReactionOwner("personal", item.record.groupIdHex, item.record.messageIdHex)
        val projected = checkNotNull(item.projected)
        val memberAuthored = projected.groupSystem!!.copy(provenance = GroupSystemEventProvenanceFfi.MEMBER_AUTHORED)
        assertTrue(groupSystemReactionEligible(item, owner))
        listOf(
            item.copy(projected = null),
            item.copy(projected = projected.copy(groupSystem = memberAuthored)),
            item.copy(projected = projected.copy(deleted = true)),
            item.copy(projected = projected.copy(invalidationStatus = "BeyondAppRetention")),
            item.copy(projected = projected.copy(groupSystem = null)),
            item.copy(projected = projected.copy(groupSystem = projected.groupSystem!!.copy(actorAccountIdHex = null))),
            item.copy(record = item.record.copy(direction = "received")),
        ).forEach { assertFalse(groupSystemReactionEligible(it, owner)) }
        retain(item)
        assertNull(currentGroupSystemReactionTarget(pollController, owner.copy(accountRef = "other")))
        assertNull(currentGroupSystemReactionTarget(pollController, owner.copy(groupId = "other")))
        pollController.onCleared()
        assertNull(currentGroupSystemReactionTarget(pollController, owner))
    }

    /** Renders a native authenticated activity, using TimelineRow's actual dispatch. */
    private fun render(): TimelineMessage =
        activity().also { item ->
            retain(item)
            mountedItem.value = item
            composeRule.setContent {
                WhiteNoiseTheme {
                    Surface(Modifier.fillMaxWidth()) {
                        RealPollMessage(mountedItem.value!!, menuOpen.value, readOnly.value) { menuOpen.value = it }
                    }
                }
            }
        }

    /** TalkBack/keyboard invoke the same semantic long press as touch. */
    private fun openMenu() {
        composeRule
            .onNodeWithText("Alice changed the group avatar")
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
    }

    /** Advances the shared reaction conflator's debounce on Android Main. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    }
}
