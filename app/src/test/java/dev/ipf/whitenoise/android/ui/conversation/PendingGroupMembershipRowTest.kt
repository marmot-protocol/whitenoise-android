package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.state.OptimisticGroupRosterMutation
import dev.ipf.whitenoise.android.state.OptimisticGroupRosterMutationTracker
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class PendingGroupMembershipRowTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun returningFromDetailsShowsTheSameSuspendedInviteUntilItsOwnResult() =
        runBlocking {
            val tracker = OptimisticGroupRosterMutationTracker()
            val release = CompletableDeferred<Unit>()
            var conversationVisible by mutableStateOf(false)
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    tracker.track(OptimisticGroupRosterMutation.Invite(listOf("Ada"))) { release.await() }
                }
            val identity = requireNotNull(tracker.pendingMembershipActivity).id
            rule.setContent {
                WhiteNoiseTheme {
                    Column {
                        if (conversationVisible) {
                            tracker.pendingMembershipActivity?.let {
                                PendingGroupMembershipRow(activity = it, displayName = { ref -> ref })
                            }
                        }
                    }
                }
            }
            rule.runOnIdle { conversationVisible = true }
            rule.onNodeWithText("Invite pending: Ada").assertIsDisplayed()
            rule.runOnIdle { conversationVisible = false }
            rule.onNodeWithText("Invite pending: Ada").assertDoesNotExist()
            rule.runOnIdle { conversationVisible = true }
            rule.onNodeWithText("Invite pending: Ada").assertIsDisplayed()
            assertEquals(identity, tracker.pendingMembershipActivity?.id)
            release.complete(Unit)
            result.await()
            rule.onNodeWithText("Invite pending: Ada").assertDoesNotExist()
            assertNull(tracker.pendingMembershipActivity)
        }

    @Test
    fun membershipActivityAndBottomErrorDoNotShiftReplyOrReadIndices() {
        val trailing = conversationTimelineTrailingRowCount(hasBottomError = true, hasPendingMembership = true)
        assertEquals(2, trailing)
        val timelineIndex = 37
        val listIndex = conversationTimelineListIndex(timelineIndex, timelineSize = 100, trailingRowCount = trailing)
        assertEquals(timelineIndex, conversationTimelineIndexForListIndex(listIndex, 100, trailing))
        assertEquals(2, conversationTimelineTailListIndex(100, trailing))
        assertEquals(1, conversationTimelineTailListIndex(100, trailing, hasPendingMembership = true))
        assertEquals(0, conversationTimelineTailListIndex(0, 1, hasPendingMembership = true))
    }
}
