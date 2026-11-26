package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** Runs MDK's authoritative sweep while any loaded row owns a deadline. */
internal suspend fun ConversationController.runForegroundDisappearingMessageSweep(account: String) {
    while (coroutineContext.isActive) {
        val expiryRows = foregroundSweepExpiryRows()
        if (expiryRows.any(DisappearingMessageSweep::hasLocalExpiry)) {
            val nowMillis = clockMillis()
            lastForegroundSweepStartedAtMillis = nowMillis
            // Engine-owned gate + prune: the account worker runs the
            // skew/unread deferrals atomically with the prune, serialized
            // against this conversation's own sends. Android applies the
            // pruned outcome — tray-card dismissal (#333) and media-cache
            // eviction (#334) — for this group here, with precise keys
            // from the loaded timeline; any other pruned group of the
            // account goes through the shared closed-conversation path.
            val outcome =
                appState.runRetentionSweep(account, nowMillis, handledGroupIdHex = group.groupIdHex)
            if (outcome != null) {
                if (outcome.prunedMessages > 0uL) {
                    appState.dismissConversationNotifications(account, group.groupIdHex)
                }
                evictExpiredMediaCaches(account, outcome.mediaCiphertextSha256.toSet())
            }
            publishTimelineFromIndexes()
        }
        awaitForegroundDisappearingSweepSchedule()
    }
}

/** Drains stale wake signals, then waits until the next loaded-row deadline or a schedule change makes a sweep due. */
private suspend fun ConversationController.awaitForegroundDisappearingSweepSchedule() {
    while (coroutineContext.isActive) {
        while (foregroundSweepScheduleSignals.tryReceive().isSuccess) {
            // Drop stale self-signals before sleeping; the next timeout is
            // based on the timeline/group state that is current right now.
        }
        val delayMillis =
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = clockMillis(),
                rows = foregroundSweepExpiryRows(),
            )
        val wakeSignalReceived =
            withTimeoutOrNull(delayMillis) {
                foregroundSweepScheduleSignals.receive()
                true
            } == true
        if (shouldRunForegroundSweepAfterWake(wakeSignalReceived)) return
    }
}

/**
 * Projects loaded rows in chronological display order into their row-owned expiry inputs, followed by the
 * projected twins of optimistic rows. Both copies of a raw id contribute, so a projected deadline still schedules
 * the sweep when its optimistic copy wins the position. The display sort runs only when some row owns a
 * deadline, because rows without one never resolve a local expiry.
 */
internal fun ConversationController.foregroundSweepExpiryRows(): List<DisappearingMessageSweep.LocalExpiryRow> {
    val projected = loadedProjectedRows()
    val (candidates, messageOrder) =
        if (optimisticMessages.values.none(::hasLocalExpiryDeadline) && projected.none(::hasLocalExpiryDeadline)) {
            // No row can resolve an expiry, so skip the display sort on this main-thread path.
            (optimisticMessages.values + projected) to emptyMap()
        } else {
            val ordered = loadedTimelineMessagesInDisplayOrder()
            val optimisticIds = optimisticMessages.values.mapTo(HashSet()) { it.record.messageIdHex }
            // Projected twins of optimistic rows lose the display position but keep their own deadline.
            val twins = projected.filter { it.record.messageIdHex in optimisticIds }
            (ordered + twins) to firstMessageOrder(ordered.map { it.record.messageIdHex })
        }
    return candidates
        .filter { shouldApplyLocalDisappearingExpiry(it.record) }
        .map { localExpiryRow(it, messageOrder) }
}

/**
 * Returns every loaded optimistic and projected row in chronological display order so expiry
 * deferral, sweep scheduling, and read anchoring agree with the displayed transcript while
 * preserving optimistic precedence by raw message id before display ordering.
 */
internal fun ConversationController.loadedTimelineMessagesInDisplayOrder(): List<TimelineMessage> {
    val rows = (optimisticMessages.values + loadedProjectedRows()).distinctBy { it.record.messageIdHex }
    val memo = displayOrderMemo
    // The publish, the sweep and per-row lookups share one sort until a loaded row changes.
    if (memo != null && memo.first.size == rows.size && rows.indices.all { rows[it] === memo.first[it] }) {
        return memo.second
    }
    return orderTimelineMessagesForDisplay(rows).also { displayOrderMemo = rows to it }
}

/** The display-order index map [item]'s expiry deferral needs, or empty when [item] owns no local deadline. */
internal fun ConversationController.expiryOrderFor(item: TimelineMessage): Map<String, Int> {
    if (!hasLocalExpiryDeadline(item)) return emptyMap()
    return firstMessageOrder(loadedTimelineMessagesInDisplayOrder().map { it.record.messageIdHex })
}

/** Builds expiry input without consulting the group's mutable policy. */
internal fun ConversationController.localExpiryRow(
    message: TimelineMessage,
    messageOrder: Map<String, Int>,
): DisappearingMessageSweep.LocalExpiryRow {
    val record = message.record
    val authoritativeExpiry = record.retentionExpiresAt?.takeIf { it > 0uL }
    return DisappearingMessageSweep.LocalExpiryRow(
        timelineAtSeconds = record.recordedAt,
        // The engine's authoritative per-message expiry wins over the
        // send-time + retention arithmetic below when it is projected.
        // Zero is not a real expiry (the engine emits null when retention
        // is off); guard like message info does so a zero could never
        // read as epoch-expired and hide the row instantly.
        expiresAtLocalSeconds = authoritativeExpiry,
        retentionAtSendSeconds =
            record.retentionSeconds?.takeIf { authoritativeExpiry != null && it > 0uL }
                ?: message.retentionAtSendSeconds?.takeIf { it > 0uL },
        readAnchoredAtSeconds = readAnchoredAtSeconds[record.messageIdHex],
        // The account's own message from another device expires like its own sends, with no read deferral.
        deferSendTimeExpiry =
            !message.fromOtherOwnDevice &&
                isDisappearingSendTimeExpiryDeferred(
                    record = record,
                    lastReadMessageId = lastReadMessageId,
                    lastReadTimelineAt = persistedLastReadTimelineAt,
                    messageOrder = messageOrder,
                ),
    )
}

/** The retained row's expiry input, ordered chronologically like the timeline filter and the sweep. */
internal fun ConversationController.retainedExpiryRow(messageId: String): Pair<TimelineMessage, DisappearingMessageSweep.LocalExpiryRow>? {
    val item = retainedTimelineItem(messageId) ?: return null
    return item to localExpiryRow(item, expiryOrderFor(item))
}

/** The wall-clock deadline in milliseconds of the retained row [messageId], or null without a resolved deadline. */
internal fun ConversationController.retainedRowExpiryDeadlineMillis(messageId: String): Long? {
    val seconds =
        retainedExpiryRow(messageId)?.let { (item, row) ->
            DisappearingMessageSweep
                .resolveLocalExpirySeconds(row)
                ?.takeIf { shouldApplyLocalDisappearingExpiry(item.record) }
        } ?: return null
    return java.util.concurrent.TimeUnit.SECONDS
        .toMillis(seconds.coerceAtMost(Long.MAX_VALUE.toULong()).toLong())
}

internal fun ConversationController.signalForegroundSweepScheduleChanged() {
    foregroundSweepScheduleSignals.trySend(Unit)
}

/** Current wall-clock seconds from the controller's lifecycle-stable clock. */
internal fun ConversationController.nowSeconds(): ULong = (clockMillis() / MILLIS_PER_SECOND).toULong()

private const val MILLIS_PER_SECOND = 1_000L
