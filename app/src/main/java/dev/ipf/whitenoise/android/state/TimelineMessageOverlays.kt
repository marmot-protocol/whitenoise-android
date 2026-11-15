package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.EditState

/**
 * Preserve a failed optimistic text send as a local timeline row instead of
 * deleting the user's draft on publish failure. The failed row keeps the same
 * temp id/order so retry/delete affordances can operate on the live optimistic
 * state and the body remains copyable from the bubble.
 */
internal fun retainFailedOptimisticTextSend(
    optimisticMessages: MutableMap<String, TimelineMessage>,
    messageById: MutableMap<String, AppMessageRecordFfi>,
    key: String,
    optimistic: AppMessageRecordFfi,
    timelineOrder: ULong,
) {
    val retentionAtSendSeconds = optimisticMessages[key]?.retentionAtSendSeconds
    messageById[optimistic.messageIdHex] = optimistic
    optimisticMessages[key] =
        TimelineMessage(
            key,
            optimistic,
            MessageStatus.Failed,
            timelineOrder = timelineOrder,
            retentionAtSendSeconds = retentionAtSendSeconds,
        )
}

/**
 * Trim a message-keyed [map] to the ids still referenced by the loaded window
 * ([windowIds]) or by in-flight [optimisticMessages].
 *
 * Every per-message map that sits beside the timeline window uses this one
 * retain set: decrypted records, read anchors, retention-at-send values and
 * optimistic edits. The live Projection/Upsert path adds entries but never
 * removes ones that scroll out, so without this trim a long-lived busy
 * conversation keeps decrypted content far beyond the rendered window (#373).
 * This is the same retain set the window-replace path applies on page load (#68).
 *
 * Window rows can be evicted between calls on one apply pass, so the set is
 * built from the current [windowIds] and [optimisticMessages] on each call.
 */
internal fun retainWindowMessageKeys(
    map: MutableMap<String, *>,
    windowIds: Collection<String>,
    optimisticMessages: Collection<TimelineMessage>,
) {
    if (map.isEmpty()) return
    val retain = HashSet(windowIds)
    optimisticMessages.forEach { retain.add(it.record.messageIdHex) }
    map.keys.retainAll(retain)
}

internal fun retainedMessageOverlayTargets(
    timelineMessageIds: Collection<String>,
    optimisticMessageIds: Collection<String>,
    overlayTargetIds: Collection<String>,
): Set<String> {
    val present = HashSet(timelineMessageIds)
    present.addAll(optimisticMessageIds)
    return overlayTargetIds.filterTo(linkedSetOf()) { it in present }
}

/** Confirms only a published edit whose wire aggregate or exact native revision agrees. */
internal fun isConfirmedOptimisticEdit(
    edit: OptimisticEdit,
    confirmedEdit: EditState?,
    record: TimelineMessageRecordFfi?,
): Boolean {
    val confirmedWireEdit = confirmedEdit?.latestText == edit.text
    val confirmedNativeEdit =
        edit.nativeEditMessageId != null &&
            record?.edit?.latestEditMessageIdHex == edit.nativeEditMessageId &&
            record.plaintext == edit.text
    return edit.status == MessageStatus.Sent && (confirmedWireEdit || confirmedNativeEdit)
}

/**
 * Overlay the optimistic edit text onto [aggregated] so the bubble renders
 * the edited body immediately. A Pending/Sent overlay shows its text; a
 * Failed overlay shows [OptimisticEdit.preEditText] (the revert target).
 */
internal fun applyOptimisticEdits(
    aggregated: Map<String, EditState>,
    optimisticEdits: Map<String, OptimisticEdit>,
): Map<String, EditState> {
    if (optimisticEdits.isEmpty()) return aggregated
    val merged = LinkedHashMap(aggregated)
    for ((target, edit) in optimisticEdits) {
        val failed = edit.status == MessageStatus.Failed
        val displayText = if (failed) edit.preEditText else edit.text
        val base = merged[target]
        when {
            base != null -> merged[target] = base.copy(latestText = displayText)
            // No real kind-1009 was accepted yet (null base). Synthesize an edit
            // aggregate only for an applied overlay (Pending/Sent) so the bubble
            // shows the optimistic body with an edited indicator. A Failed edit
            // reverts to the original text and never accepted a kind-1009, so
            // leave the target absent — no spurious "edited" badge.
            !failed -> merged[target] = EditState(latestText = displayText, count = 1, versions = emptyList())
        }
    }
    return merged
}

/**
 * Surface an in-flight optimistic edit as the target bubble's status so the
 * existing Sending indicator / Failed retry+discard row light up without a
 * new affordance. Durable revisions can also override a pending original;
 * a failed original keeps its own retry status until that send resolves.
 */
internal fun TimelineMessage.withOptimisticEditStatus(edit: OptimisticEdit?): TimelineMessage {
    val canShowEditStatus =
        status == MessageStatus.Sent || (edit?.durableIntent != null && status == MessageStatus.Pending)
    if (edit == null || !canShowEditStatus) return this
    val fromEdit = status == MessageStatus.Sent
    return when (edit.status) {
        MessageStatus.Pending -> copy(status = MessageStatus.Pending, statusFromEdit = fromEdit)
        MessageStatus.Failed -> copy(status = MessageStatus.Failed, statusFromEdit = fromEdit)
        else -> this
    }
}
