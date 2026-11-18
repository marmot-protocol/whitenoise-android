package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
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
import org.junit.Assert.assertEquals
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

    /** Text that lost convergence never forwards, while the same text on a valid row forwards that exact text. */
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
        assertEquals(
            ForwardMessagePayload.Text(
                sourceGroupIdHex = ConversationTimelineTestIds.GROUP_ID,
                sourceMessageIdHex = VALID,
                text = TEXT,
            ),
            valid.forwardPayload,
        )
        assertNull(valid.forwardBlockedReason)
    }

    /**
     * An edit the controller holds, such as one still awaiting its echo, is the text bulk copy and forward use for
     * its own message only, while an unedited row keeps its original text.
     */
    @Test
    fun bulkCopyAndForwardUseTheControllersEditedText() {
        val selections =
            project(
                timelineRecord(VALID, timelineAt = 1uL, plaintext = TEXT),
                timelineRecord(UNEDITED, timelineAt = 2uL, plaintext = UNEDITED_TEXT),
                edits = mapOf(VALID to EditState(latestText = EDITED, count = 1, versions = emptyList())),
            )
        val edited = selections.getValue(VALID).action
        val unedited = selections.getValue(UNEDITED).action

        assertEquals(EDITED, edited.copyableText)
        assertEquals(EDITED, edited.forwardableText)
        assertEquals(textPayload(VALID, EDITED), edited.forwardPayload)
        assertEquals(UNEDITED_TEXT, unedited.copyableText)
        assertEquals(UNEDITED_TEXT, unedited.forwardableText)
        assertEquals(textPayload(UNEDITED, UNEDITED_TEXT), unedited.forwardPayload)
    }

    /** The text forward payload a message in the fixture group carries. */
    private fun textPayload(
        messageId: String,
        text: String,
    ) = ForwardMessagePayload.Text(
        sourceGroupIdHex = ConversationTimelineTestIds.GROUP_ID,
        sourceMessageIdHex = messageId,
        text = text,
    )

    /**
     * Opens a controller on [records], applies [edits] as its edit state, then projects its rendered timeline as
     * the screen's batch selection does.
     */
    private fun project(
        vararg records: TimelineMessageRecordFfi,
        edits: Map<String, EditState> = emptyMap(),
    ): Map<String, BatchMessageSelection> {
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(ScriptedConversationTimelineSubscription(timelinePage(*records))),
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
        return try {
            awaitConversationCondition { controller.timeline.size == records.size }
            if (edits.isNotEmpty()) controller.editsByTarget = edits
            projectSelectableMessages(controller.timeline, controller, eligibilityNowSeconds = 0uL)
        } finally {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripted)
        }
    }

    private companion object {
        const val TEXT = "hello"
        const val EDITED = "hello, edited"
        const val UNEDITED = "unedited"
        const val UNEDITED_TEXT = "left as sent"
        const val LOSING_BRANCH = "losing-branch"
        const val PERSISTED_FAILURE = "persisted-failure"
        const val VALID = "valid"
    }
}
