package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector

/**
 * Keep a local send-time retention hint only while it can bridge an actual
 * optimistic handoff or the conversation still has retention enabled. A null
 * authoritative projection after retention is disabled must retire any
 * remembered hint instead of re-seeding it from the previous UI row.
 */
internal fun retentionHintForProjection(
    projectedRetentionSeconds: ULong?,
    currentGroupRetentionSeconds: ULong,
    optimisticSnapshot: ULong?,
    rememberedSnapshot: ULong?,
): ULong? =
    when {
        projectedRetentionSeconds != null -> null
        optimisticSnapshot != null -> optimisticSnapshot
        currentGroupRetentionSeconds == 0uL -> null
        else -> rememberedSnapshot
    }

/**
 * Whether send-time disappearing expiry should stay suspended for [record]
 * until the user scrolls past it (#797). Own sends always use send-time or a
 * display anchor; received rows after the persisted read watermark stay
 * visible even when their wire send-time expiry has passed.
 */
internal fun isDisappearingSendTimeExpiryDeferred(
    record: AppMessageRecordFfi,
    lastReadMessageId: String?,
    lastReadTimelineAt: ULong?,
    messageOrder: Map<String, Int>,
): Boolean {
    if (record.direction != "received") return false
    lastReadMessageId?.takeIf { it.isNotBlank() }?.let { anchorId ->
        val anchorIdx = messageOrder[anchorId]
        val msgIdx = messageOrder[record.messageIdHex]
        if (anchorIdx != null && msgIdx != null) return msgIdx > anchorIdx
    }
    if (lastReadTimelineAt != null) return record.recordedAt > lastReadTimelineAt
    return true
}

/**
 * Group-system rows are durable history about the conversation itself, including the event that
 * announces a retention change. Applying the current message window to those rows hides an old
 * retention event on controller recreation, then a session read anchor can make it reappear after
 * the next send. All other projected row kinds retain their existing expiry behavior.
 */
internal fun shouldApplyLocalDisappearingExpiry(record: AppMessageRecordFfi): Boolean {
    return !MessageProjector.isGroupSystem(record)
}

/** Applies the row-owned deadline only to ordinary message history. */
internal fun isTimelineRecordLocallyExpired(
    nowMillis: Long,
    record: AppMessageRecordFfi,
    row: DisappearingMessageSweep.LocalExpiryRow,
): Boolean =
    shouldApplyLocalDisappearingExpiry(record) &&
        DisappearingMessageSweep.isLocallyExpired(
            nowMillis = nowMillis,
            row = row,
        )

internal fun firstMessageOrder(messageIds: Iterable<String>): Map<String, Int> =
    buildMap {
        messageIds.forEachIndexed { index, messageId -> putIfAbsent(messageId, index) }
    }
