package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi

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
