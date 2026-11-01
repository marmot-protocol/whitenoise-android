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
 * Trim [messageById] to the records still referenced by the loaded window
 * ([windowIds]) or by in-flight [optimisticMessages], dropping projected records
 * that have scrolled out of the window.
 *
 * [messageById] holds full decrypted records (plaintext + parsed tokens). The
 * live Projection/Upsert path adds one per delivered message but never trims, so
 * a long-lived busy conversation accumulates decrypted content far beyond the
 * rendered window — a memory-growth and privacy-footprint issue (#373). This is
 * the same retain-set the window-replace path already applies on page load (#68).
 */
internal fun pruneMessageByIdToWindow(
    messageById: MutableMap<String, AppMessageRecordFfi>,
    windowIds: Set<String>,
    optimisticMessages: Collection<TimelineMessage>,
) {
    val retain = HashSet(windowIds)
    optimisticMessages.forEach { retain.add(it.record.messageIdHex) }
    messageById.keys.retainAll(retain)
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
