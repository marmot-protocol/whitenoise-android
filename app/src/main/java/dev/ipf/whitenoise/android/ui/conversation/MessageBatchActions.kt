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
    val mine: Boolean,
)

/** Selection snapshot retained independently of the controller's bounded timeline window. */
internal data class BatchMessageSelection(
    val action: BatchMessageActionItem,
    val record: AppMessageRecordFfi,
    val timelineOrder: ULong,
)

internal data class BatchDeleteBreakdown(
    val deleteForEveryone: Int,
    val hideLocally: Int,
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
    capEvictedMessageIds: Set<String>,
    deletedMessageIds: Set<String>,
): Map<String, BatchMessageSelection> =
    buildMap {
        selected.forEach { (messageId, snapshot) ->
            when {
                messageId in deletedMessageIds -> Unit
                messageId in selectableVisible -> put(messageId, selectableVisible.getValue(messageId))
                messageId in capEvictedMessageIds -> put(messageId, snapshot)
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
        deleteForEveryone = items.count { it.mine },
        hideLocally = items.count { !it.mine },
    )
