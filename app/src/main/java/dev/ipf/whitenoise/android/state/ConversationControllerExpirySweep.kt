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

private suspend fun ConversationController.awaitForegroundDisappearingSweepSchedule() {
    while (coroutineContext.isActive) {
        while (foregroundSweepScheduleSignals.tryReceive().isSuccess) {
            // Drop stale self-signals before sleeping; the next timeout is
            // based on the timeline/group state that is current right now.
        }
        val wakeSignalReceived =
            withTimeoutOrNull(foregroundDisappearingSweepDelayMillis()) {
                foregroundSweepScheduleSignals.receive()
                true
            } == true
        if (shouldRunForegroundSweepAfterWake(wakeSignalReceived)) return
    }
}

/** Returns the next loaded row deadline, capped to the foreground cadence. */
private fun ConversationController.foregroundDisappearingSweepDelayMillis(): Long =
    DisappearingMessageSweep.nextForegroundSweepDelayMillis(
        nowMillis = clockMillis(),
        rows = foregroundSweepExpiryRows(),
    )

/** Projects loaded timeline messages into their row-owned expiry inputs. */
internal fun ConversationController.foregroundSweepExpiryRows(): List<DisappearingMessageSweep.LocalExpiryRow> {
    val messages = sweepExpiryMessages()
    val messageOrder = firstMessageOrder(messages.map { it.record.messageIdHex })
    return messages
        .filter { shouldApplyLocalDisappearingExpiry(it.record) }
        .map { localExpiryRow(it, messageOrder) }
}

/** Returns every loaded row so a group-system read anchor keeps its ordering position. */
internal fun ConversationController.sweepExpiryMessages(): List<TimelineMessage> =
    buildList {
        addAll(optimisticMessages.values)
        timelineOrder.mapNotNullTo(this) { timelineItemsById[it] }
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

/** The retained row's expiry input, ordered like the timeline filter and the sweep (optimistic ids first). */
internal fun ConversationController.retainedExpiryRow(messageId: String): Pair<TimelineMessage, DisappearingMessageSweep.LocalExpiryRow>? {
    val item = retainedTimelineItem(messageId) ?: return null
    val order = firstMessageOrder(sweepExpiryMessages().map { it.record.messageIdHex })
    return item to localExpiryRow(item, order)
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
