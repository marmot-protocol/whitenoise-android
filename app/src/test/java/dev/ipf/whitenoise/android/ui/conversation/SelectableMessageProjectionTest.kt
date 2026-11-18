package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.awaitOpenedTimelineSubscriptionsClosed
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Batch selection entries projected from a real controller's rendered rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class SelectableMessageProjectionTest {
    /** Text that lost convergence stays available to bulk copy, while a persisted failure copies nothing. */
    @Test
    fun losingBranchTextStaysCopyableWhilePersistedFailureCopiesNothing() {
        val selections =
            project(
                timelineRecord(LOSING_BRANCH, timelineAt = 1uL, plaintext = TEXT)
                    .copy(invalidationStatus = "LosingBranch"),
                timelineRecord(PERSISTED_FAILURE, timelineAt = 2uL, plaintext = TEXT)
                    .copy(invalidationStatus = "FutureReason"),
            )

        assertEquals(TEXT, selections.getValue(LOSING_BRANCH).action.copyableText)
        assertNull(selections.getValue(PERSISTED_FAILURE).action.copyableText)
    }

    /** Text that lost convergence never forwards, while the same text on a valid row carries a forward payload. */
    @Test
    fun losingBranchTextIsBlockedFromForwardWhileValidTextForwards() {
        val invalidated =
            project(
                timelineRecord(LOSING_BRANCH, timelineAt = 1uL, plaintext = TEXT)
                    .copy(invalidationStatus = "LosingBranch"),
            ).getValue(LOSING_BRANCH).action
        val valid = project(timelineRecord(VALID, timelineAt = 1uL, plaintext = TEXT)).getValue(VALID).action

        assertNull(invalidated.forwardableText)
        assertNull(invalidated.forwardPayload)
        assertEquals(ForwardBlockedReason.Unsupported, invalidated.forwardBlockedReason)
        assertEquals(TEXT, valid.forwardableText)
        assertNotNull(valid.forwardPayload)
        assertNull(valid.forwardBlockedReason)
    }

    /** Opens a controller on [records], then projects its rendered timeline as the screen's batch selection does. */
    private fun project(vararg records: TimelineMessageRecordFfi): Map<String, BatchMessageSelection> =
        runBlocking {
            val scripted =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts =
                        listOf(ScriptedConversationTimelineSubscription(timelinePage(*records))),
                    group = conversationTimelineTestGroup(),
                )
            val controller =
                ConversationController(
                    appState = conversationTimelineTestAppState(scripted.subscriptions),
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                    groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                    startOnConstruction = true,
                )
            try {
                awaitConversationCondition { controller.timeline.size == records.size }
                projectSelectableMessages(controller.timeline, controller, eligibilityNowSeconds = 0uL)
            } finally {
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scripted)
            }
        }

    private companion object {
        const val TEXT = "hello"
        const val LOSING_BRANCH = "losing-branch"
        const val PERSISTED_FAILURE = "persisted-failure"
        const val VALID = "valid"
    }
}
