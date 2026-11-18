package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.core.ForwardEligibility
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.usesPersistedFailurePresentation
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.attachmentsFor
import dev.ipf.whitenoise.android.state.mediaReferencesFor

/**
 * Projects every batch-selectable message in the rendered timeline to its selection entry, keyed by message
 * id. Each entry snapshots the copy and forward text, delete capability and forward eligibility at
 * [eligibilityNowSeconds]. The screen remembers the result by the inputs these projections read.
 */
internal fun projectSelectableMessages(
    renderedTimeline: List<TimelineMessage>,
    controller: ConversationController,
    eligibilityNowSeconds: ULong,
): Map<String, BatchMessageSelection> =
    renderedTimeline
        .mapNotNull { item -> selectableMessageProjection(item, controller, eligibilityNowSeconds) }
        .associateBy { it.action.messageId }

/** Projects one timeline message to its batch selection entry, or null when it cannot join a batch. */
private fun selectableMessageProjection(
    item: TimelineMessage,
    controller: ConversationController,
    eligibilityNowSeconds: ULong,
): BatchMessageSelection? {
    val record = item.record
    val messageId = record.messageIdHex
    if (
        !isBatchSelectableMessage(
            messageId = messageId,
            userVisibleMessage = MessageProjector.isChatKind(record.kind),
            // A pending send joins a batch so its accepted text can be forwarded (#2794), while a
            // sent message with an edit in flight stays out of selection as before.
            committedMessage =
                item.status == MessageStatus.Received ||
                    item.status == MessageStatus.Sent ||
                    item.isPendingSend,
            projectedDeleted = item.projected?.deleted == true,
            deletedMessageIds = controller.deletedMessageIds,
        )
    ) {
        return null
    }
    val invalidated = item.projected?.invalidationStatus != null
    val persistedFailure = item.projected?.let(::usesPersistedFailurePresentation) == true
    val editedText =
        controller.editsByTarget[messageId]
            ?.latestText
            ?.takeIf { record.kind == 9uL }
    val mediaReferences = controller.mediaReferencesFor(item)
    val forwardEligibility =
        if (invalidated || persistedFailure) {
            ForwardEligibility.Blocked(ForwardBlockedReason.Unsupported)
        } else {
            batchForwardEligibility(item, controller, mediaReferences, editedText, eligibilityNowSeconds)
        }
    return BatchMessageSelection(
        action =
            BatchMessageActionItem(
                messageId = messageId,
                senderId = record.sender,
                senderDisplayName = record.sender,
                copyableText =
                    if (persistedFailure) {
                        null
                    } else {
                        MessageProjector.copyableText(record, editedText)
                    },
                forwardableText =
                    if (invalidated) {
                        null
                    } else {
                        MessageProjector.forwardableText(record, editedText)
                    },
                // Same authoritative accessor the single-message
                // surface and the mutation guard use, so bulk
                // routing never diverges from per-message policy.
                canDeleteForEveryone = controller.deleteCapabilityFor(record).canDeleteForEveryone,
                hasSaveableMedia = mediaReferences.isNotEmpty(),
                forwardPayload = (forwardEligibility as? ForwardEligibility.Eligible)?.payload,
                forwardBlockedReason = (forwardEligibility as? ForwardEligibility.Blocked)?.reason,
                pendingSend = item.isPendingSend,
            ),
        record = record,
        status = item.status,
        timelineOrder = item.timelineOrder,
    )
}

/**
 * Forward eligibility of a message whose text is still valid. A pending send forwards its accepted text,
 * and a delivered message forwards its attachments only once they are cached on this device.
 */
private fun batchForwardEligibility(
    item: TimelineMessage,
    controller: ConversationController,
    mediaReferences: List<MediaAttachmentReferenceFfi>,
    editedText: String?,
    eligibilityNowSeconds: ULong,
): ForwardEligibility {
    val record = item.record
    return if (item.isPendingSend) {
        MessageProjector.pendingForwardEligibility(
            message = record,
            mediaReferences = mediaReferences,
            editedText = editedText,
            nowSeconds = eligibilityNowSeconds,
        )
    } else {
        MessageProjector.forwardEligibility(
            message = record,
            mediaReferences = mediaReferences,
            editedText = editedText,
            cachedAttachmentIndices =
                controller
                    .attachmentsFor(item)
                    .map { it.index }
                    .filterTo(mutableSetOf()) { attachmentIndex ->
                        controller.hasCachedAttachment(record.messageIdHex, attachmentIndex)
                    },
            nowSeconds = eligibilityNowSeconds,
        )
    }
}
