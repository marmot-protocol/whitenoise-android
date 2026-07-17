package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector

/** UI-ready projection of one selected, user-visible message in timeline order. */
internal data class BatchMessageActionItem(
    val messageId: String,
    val senderId: String,
    val senderDisplayName: String,
    val copyableText: String?,
    val forwardableText: String?,
    /**
     * Whether this message can be removed for the whole group — the authored
     * message, or another member's message the selecting user may moderate
     * (group admin, never a direct conversation). Derived from the same
     * `deleteCapabilityFor` the single-message surface and the mutation guard
     * use, so batch routing never diverges from the per-message policy.
     * Messages without it can only be hidden on this device.
     */
    val canDeleteForEveryone: Boolean,
)

/**
 * Which removal the user picked in the batch confirm. [EVERYONE] removes each
 * message for the whole group where the user is allowed to and hides the rest
 * on this device; [LOCAL_ONLY] hides every selected message on this device and
 * publishes nothing.
 */
internal enum class BatchDeleteScope {
    EVERYONE,
    LOCAL_ONLY,
}

/** Selection snapshot retained independently of the controller's bounded timeline window. */
internal data class BatchMessageSelection(
    val action: BatchMessageActionItem,
    val record: AppMessageRecordFfi,
    val timelineOrder: ULong,
)

internal data class BatchDeleteBreakdown(
    val deleteForEveryone: Int,
    val hideLocally: Int,
) {
    /** At least one selected message can be removed for the whole group. */
    val canOfferDeleteForEveryone: Boolean get() = deleteForEveryone > 0
}

internal data class BatchDeleteResult(
    val attempted: Int,
    val succeeded: Int,
)

internal fun isBatchSelectableMessage(
    messageId: String,
    userVisibleMessage: Boolean,
    committedMessage: Boolean,
    projectedDeleted: Boolean,
    deletedMessageIds: Set<String>,
): Boolean =
    messageId.isNotBlank() &&
        userVisibleMessage &&
        committedMessage &&
        !projectedDeleted &&
        messageId !in deletedMessageIds

internal fun batchForwardSheetOpenForBodies(
    currentlyOpen: Boolean,
    forwardBodies: List<String>,
): Boolean = currentlyOpen && forwardBodies.isNotEmpty()

internal fun reconcileBatchSelections(
    selected: Map<String, BatchMessageSelection>,
    selectableVisible: Map<String, BatchMessageSelection>,
    deletedMessageIds: Set<String>,
    invalidVisibleMessageIds: Set<String>,
): Map<String, BatchMessageSelection> =
    buildMap {
        selected.forEach { (messageId, snapshot) ->
            when {
                messageId in deletedMessageIds || messageId in invalidVisibleMessageIds -> Unit
                messageId in selectableVisible -> put(messageId, selectableVisible.getValue(messageId))
                else -> put(messageId, snapshot)
            }
        }
    }

internal fun orderedBatchSelections(selections: Collection<BatchMessageSelection>): List<BatchMessageSelection> =
    selections.sortedWith(
        compareBy<BatchMessageSelection> { it.record.recordedAt }
            .thenBy { it.timelineOrder }
            .thenBy { it.action.messageId },
    )

internal fun batchCopyText(items: List<BatchMessageActionItem>): String {
    val copyable =
        items.mapNotNull { item ->
            val text = item.copyableText?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            item to text
        }
    val multipleSenders = copyable.map { (item, _) -> item.senderId.lowercase() }.distinct().size > 1
    return copyable.joinToString("\n") { (item, text) ->
        if (multipleSenders) {
            "${item.senderDisplayName.ifBlank { item.senderId }}: $text"
        } else {
            text
        }
    }
}

internal fun batchForwardBodies(items: List<BatchMessageActionItem>): List<String> =
    MessageProjector.validatedForwardTextBodies(items.map(BatchMessageActionItem::forwardableText))

internal fun batchDeleteBreakdown(items: List<BatchMessageActionItem>): BatchDeleteBreakdown =
    BatchDeleteBreakdown(
        deleteForEveryone = items.count(BatchMessageActionItem::canDeleteForEveryone),
        hideLocally = items.count { !it.canDeleteForEveryone },
    )

/**
 * Apply the chosen [scope] to every selection. In [BatchDeleteScope.EVERYONE]
 * each message the user may remove for the group is published as a delete and
 * the rest fall back to a local hide (mirroring the per-message capability); in
 * [BatchDeleteScope.LOCAL_ONLY] every message is hidden on this device and
 * nothing is published.
 */
internal suspend fun executeBatchDelete(
    selections: List<BatchMessageSelection>,
    scope: BatchDeleteScope,
    deleteForEveryone: suspend (AppMessageRecordFfi) -> Boolean,
    hideLocally: suspend (String) -> Boolean,
): BatchDeleteResult {
    var succeeded = 0
    selections.forEach { selection ->
        val removeForEveryone = scope == BatchDeleteScope.EVERYONE && selection.action.canDeleteForEveryone
        val removed =
            if (removeForEveryone) {
                deleteForEveryone(selection.record)
            } else {
                hideLocally(selection.action.messageId)
            }
        if (removed) succeeded += 1
    }
    return BatchDeleteResult(attempted = selections.size, succeeded = succeeded)
}
