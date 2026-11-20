package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppGroupRecordFfi
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * A row the projection already marks deleted, whose id was never tombstoned locally, is a confirmed placeholder
     * for the author and for an admin viewing another member's message. Both route to a local hide even when the
     * whole batch is deleted for everyone, never to the protocol delete.
     */
    @Test
    fun projectionDeletedPlaceholdersAreRoutedToLocalHideOnly() {
        val own =
            timelineRecord(OWN_DELETED, timelineAt = 1uL)
                .copy(sender = ConversationTimelineTestIds.ACCOUNT_ID, direction = "sent", deleted = true)
        val others = timelineRecord(OTHER_DELETED, timelineAt = 2uL).copy(deleted = true)
        val selections = project(own, others)

        for (id in listOf(OWN_DELETED, OTHER_DELETED)) {
            val selection = selections.getValue(id)
            assertTrue(selection.action.deleted)
            assertFalse(selection.action.canDeleteForEveryone)
            assertNull(selection.live)
        }
        val operations =
            batchDeleteAttempts(selections.values.toList(), BatchDeleteScope.EVERYONE).map { it.operation }
        assertEquals(List(2) { BatchDeleteOperationKind.HideLocally }, operations)
        // The same rows, if live, would be deletable for everyone: the author's and an admin's.
        val live = project(timelineRecord(OTHER_DELETED, timelineAt = 2uL)).getValue(OTHER_DELETED)
        assertTrue(live.action.canDeleteForEveryone)
    }

    /** A live row selects, and a deleted row selects only while local cleanup is available. */
    @Test
    fun placeholderSelectabilityFollowsLocalCleanupAvailability() {
        val deleted = timelineRecord(OTHER_DELETED, timelineAt = 2uL).copy(deleted = true)
        val live = timelineRecord(VALID, timelineAt = 1uL)

        val withCleanup = project(live, deleted)
        assertEquals(setOf(VALID, OTHER_DELETED), withCleanup.keys)
        assertFalse(withCleanup.getValue(VALID).action.deleted)
        assertTrue(withCleanup.getValue(OTHER_DELETED).action.deleted)

        // A pending invitation cannot clean up locally, so the placeholder stays read-only.
        val readOnly = project(live, deleted, group = conversationTimelineTestGroup().copy(pendingConfirmation = true))
        assertEquals(setOf(VALID), readOnly.keys)
    }

    /** An optimistic id has no stored copy to hide, so a deleted one is never selectable. */
    @Test
    fun deletedOptimisticIdsAreNotSelectable() {
        val optimistic = "11111111-2222-3333-4444-555555555555"
        val selections = project(timelineRecord(optimistic, timelineAt = 1uL).copy(deleted = true))
        assertTrue(selections.isEmpty())
    }

    /**
     * In the Everyone scope a live row is deleted for everyone, while the author's own placeholder and an admin's view
     * of another member's placeholder are only hidden locally: one protocol delete and two local hides.
     */
    @Test
    fun everyoneScopeSendsOnlyTheLiveRowToTheProtocolDelete() =
        runBlocking {
            val selections =
                project(
                    timelineRecord(VALID, timelineAt = 1uL),
                    timelineRecord(OWN_DELETED, timelineAt = 2uL)
                        .copy(sender = ConversationTimelineTestIds.ACCOUNT_ID, direction = "sent", deleted = true),
                    timelineRecord(OTHER_DELETED, timelineAt = 3uL).copy(deleted = true),
                )
            val protocol = mutableListOf<String>()
            val hidden = mutableListOf<String>()

            val attempts = batchDeleteAttempts(orderedBatchSelections(selections.values), BatchDeleteScope.EVERYONE)
            val result =
                executeBatchDelete(
                    attempts,
                    deleteForEveryone = {
                        protocol += it.messageIdHex
                        Result.success(Unit)
                    },
                    hideLocally = {
                        hidden += it
                        Result.success(Unit)
                    },
                )

            assertEquals(3, result.outcomes.size)
            assertEquals(listOf(VALID), protocol)
            assertEquals(listOf(OWN_DELETED, OTHER_DELETED), hidden)
        }

    /**
     * Eligibility is derived again from the current projection. A row that was deletable for everyone when the batch
     * was selected but is projection-deleted by the time the dialog resolves reaches only the local hide.
     */
    @Test
    fun rowDeletedAfterSelectionIsNoLongerSentToTheProtocolDelete() {
        val first = timelineRecord(VALID, timelineAt = 1uL)
        val second = timelineRecord(UNEDITED, timelineAt = 2uL)
        val before = project(first, second)
        assertEquals(
            List(2) { BatchDeleteOperationKind.DeleteForEveryone },
            batchDeleteAttempts(orderedBatchSelections(before.values), BatchDeleteScope.EVERYONE).map { it.operation },
        )

        val after = project(first, second.copy(deleted = true))
        val reconciled =
            reconcileBatchSelections(
                selected = before,
                selectableVisible = after,
                deletedMessageIds = emptySet(),
                invalidVisibleMessageIds = emptySet(),
            )
        val operations =
            batchDeleteAttempts(orderedBatchSelections(reconciled.values), BatchDeleteScope.EVERYONE)
                .associate { it.selection.action.messageId to it.operation }

        assertEquals(BatchDeleteOperationKind.DeleteForEveryone, operations.getValue(VALID))
        assertEquals(BatchDeleteOperationKind.HideLocally, operations.getValue(UNEDITED))
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
        group: AppGroupRecordFfi = conversationTimelineTestGroup(),
    ): Map<String, BatchMessageSelection> {
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(ScriptedConversationTimelineSubscription(timelinePage(*records))),
                group = group,
            )
        val controller =
            ConversationController(
                appState = conversationTimelineTestAppState(scripted.subscriptions),
                initialGroup = group,
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
            )
        return try {
            awaitConversationCondition { controller.timeline.size == records.size && controller.canSendMessages }
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
        const val OWN_DELETED = "own-deleted"
        const val OTHER_DELETED = "other-deleted"
    }
}
