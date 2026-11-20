package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch-selection rules for projection-deleted placeholders and their reconciliation. */
class MessageBatchPlaceholderRulesTest {
    @Test
    fun onlyVisibleCommittedMessagesAndRemovablePlaceholdersAreBatchSelectable() {
        assertTrue(selectable())
        assertFalse(selectable(projectedDeleted = true))
        assertFalse(selectable(deletedIds = setOf("m1")))
        assertFalse(selectable(visible = false))
        assertFalse(selectable(id = "temp-id", committed = false))
        // A deleted row joins only while local cleanup is available; a read-only placeholder and a deleted pending
        // send (which has no committed copy to hide) stay out.
        assertTrue(selectable(projectedDeleted = true, deletedIds = setOf("m1"), removable = true))
        assertFalse(selectable(projectedDeleted = true, removable = false))
        assertFalse(selectable(id = "temp-id", committed = false, projectedDeleted = true, removable = true))
    }

    private fun selectable(
        id: String = "m1",
        visible: Boolean = true,
        committed: Boolean = true,
        projectedDeleted: Boolean = false,
        deletedIds: Set<String> = emptySet(),
        removable: Boolean = false,
    ) = isBatchSelectableMessage(
        messageId = id,
        userVisibleMessage = visible,
        committedMessage = committed,
        projectedDeleted = projectedDeleted,
        deletedMessageIds = deletedIds,
        canRemovePlaceholder = removable,
    )

    @Test
    fun offscreenSelectionRequiresLiveProjectionAfterDeletionRollback() {
        val live = testBatchSelection("live", recordedAt = 100uL, timelineOrder = 1uL, canDeleteForEveryone = true)
        val offscreen = reconcileBatchSelections(mapOf("live" to live), emptyMap(), setOf("live"), emptySet())
        val placeholder = offscreen.getValue("live")
        assertTrue(placeholder.action.deleted)
        assertFalse(placeholder.action.canDeleteForEveryone)
        assertEquals(null, placeholder.action.copyableText)
        // Repeated reconciliation while the tombstone stands preserves the placeholder.
        val again = reconcileBatchSelections(offscreen, emptyMap(), setOf("live"), emptySet())
        assertEquals(placeholder, again.getValue("live"))
        val rolledBack = reconcileBatchSelections(again, emptyMap(), emptySet(), emptySet())
        assertEquals(placeholder, rolledBack.getValue("live"))
        val visibleAgain = reconcileBatchSelections(rolledBack, mapOf("live" to live), emptySet(), emptySet())
        assertEquals(live, visibleAgain.getValue("live"))
        assertTrue(visibleAgain.getValue("live").action.canDeleteForEveryone)
    }

    @Test
    fun onScreenPlaceholderStaysDeletedAcrossOffscreenReconciliation() {
        val live = testBatchSelection("live", recordedAt = 100uL, timelineOrder = 1uL, canDeleteForEveryone = true)
        val visiblePlaceholder = live.asPlaceholder()
        val onScreen =
            reconcileBatchSelections(
                selected = mapOf("live" to live),
                selectableVisible = mapOf("live" to visiblePlaceholder),
                deletedMessageIds = setOf("live"),
                invalidVisibleMessageIds = emptySet(),
            )
        assertEquals(visiblePlaceholder, onScreen.getValue("live"))
        // An off-screen row requires live projection evidence before restoring its actions.
        val rolledBack = reconcileBatchSelections(onScreen, emptyMap(), emptySet(), emptySet())
        assertEquals(visiblePlaceholder, rolledBack.getValue("live"))
        val visibleAgain = reconcileBatchSelections(rolledBack, mapOf("live" to live), emptySet(), emptySet())
        assertEquals(live, visibleAgain.getValue("live"))
    }

    @Test
    fun confirmedProjectionPlaceholderStaysLocalOnlyOffscreen() {
        val gone = testBatchSelection("gone", recordedAt = 100uL, timelineOrder = 1uL)
        val confirmed = gone.asPlaceholder()
        val retained = reconcileBatchSelections(mapOf("gone" to confirmed), emptyMap(), emptySet(), emptySet())
        assertTrue(retained.getValue("gone").action.deleted)
        assertFalse(retained.getValue("gone").action.canDeleteForEveryone)
    }
}

/** Builds a batch selection for the given message, optionally deletable for everyone. */
internal fun testBatchSelection(
    id: String,
    recordedAt: ULong,
    timelineOrder: ULong,
    canDeleteForEveryone: Boolean = false,
): BatchMessageSelection =
    BatchMessageSelection(
        action = BatchMessageActionItem(id, "alice", "Alice", id, id, canDeleteForEveryone = canDeleteForEveryone),
        record =
            AppMessageRecordFfi(
                messageIdHex = id,
                direction = "received",
                groupIdHex = "group",
                sender = "alice",
                plaintext = id,
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks = emptyList(),
                        blankLinesBefore = ByteArray(0),
                    ),
                kind = 9uL,
                tags = emptyList(),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = recordedAt,
                receivedAt = recordedAt,
                sticker = null,
            ),
        status = MessageStatus.Received,
        timelineOrder = timelineOrder,
    )
