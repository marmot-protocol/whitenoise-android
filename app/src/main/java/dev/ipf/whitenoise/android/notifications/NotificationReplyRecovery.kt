package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class NotificationReplyRecoveryState(
    val boundary: NotificationReplyRecoveryBoundary,
    val scope: String,
    val sequence: Long,
    val committedMessageIdHex: String?,
)

internal data class NotificationReplyRecoverySnapshot(
    val recoveryState: NotificationReplyRecoveryState,
    val nextAttemptBoundary: NotificationReplyRecoveryBoundary?,
)

internal sealed interface NotificationReplyRecoveryLookup {
    data object NotStarted : NotificationReplyRecoveryLookup

    data object Indeterminate : NotificationReplyRecoveryLookup

    data class Ready(
        val snapshot: NotificationReplyRecoverySnapshot,
    ) : NotificationReplyRecoveryLookup
}

internal data class NotificationReplyTimelineRecord(
    val timelineAt: ULong,
    val messageIdHex: String,
    val sourceMessageIdHex: String?,
    val direction: String,
    val plaintext: String,
)

internal data class NotificationReplyTimelinePage(
    val records: List<NotificationReplyTimelineRecord>,
    val hasMoreAfter: Boolean,
)

internal enum class NotificationReplyCommitProbe {
    Committed,
    NotCommitted,
    Indeterminate,
}

internal enum class NotificationReplySendOutcome {
    Sent,
    AlreadyCommitted,
    RetryableFailure,
    NonRetryableFailure,
}

internal fun NotificationReplyTimelineRecord.cursor(): NotificationReplyRecoveryBoundary =
    NotificationReplyRecoveryBoundary(timelineAt = timelineAt, messageIdHex = messageIdHex)

internal fun notificationReplyRecoveryBoundary(nowMillis: Long): NotificationReplyRecoveryBoundary =
    NotificationReplyRecoveryBoundary(
        timelineAt = (nowMillis.coerceAtLeast(0L) / 1_000L).toULong(),
        messageIdHex = MAX_TIMELINE_MESSAGE_ID,
    )

internal fun notificationReplySendWindowReady(
    boundary: NotificationReplyRecoveryBoundary,
    nowMillis: Long,
): Boolean = (nowMillis.coerceAtLeast(0L) / 1_000L).toULong() > boundary.timelineAt

internal suspend fun notificationReplyCommitProbe(
    recoveryState: NotificationReplyRecoveryState,
    nextAttemptBoundary: NotificationReplyRecoveryBoundary?,
    text: String,
    loadPage: suspend (after: NotificationReplyRecoveryBoundary, limit: UInt) -> NotificationReplyTimelinePage,
): NotificationReplyCommitProbe {
    val committedMessageId = recoveryState.committedMessageIdHex
    if (committedMessageId != null) {
        return if (MESSAGE_ID.matches(committedMessageId)) {
            NotificationReplyCommitProbe.Committed
        } else {
            NotificationReplyCommitProbe.Indeterminate
        }
    }

    val body = text.trim().takeIf { it.isNotEmpty() } ?: return NotificationReplyCommitProbe.Indeterminate
    val lowerBoundary = recoveryState.boundary
    if (nextAttemptBoundary != null && compareCursor(nextAttemptBoundary, lowerBoundary) <= 0) {
        return NotificationReplyCommitProbe.Indeterminate
    }

    var cursor = lowerBoundary
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = loadPage(cursor, RECOVERY_PAGE_LIMIT)
        val orderedRecords = page.records.sortedWith(compareBy({ it.timelineAt }, { it.messageIdHex }))
        for (record in orderedRecords) {
            val recordCursor = record.cursor()
            if (compareCursor(recordCursor, cursor) <= 0) continue
            if (nextAttemptBoundary != null && compareCursor(recordCursor, nextAttemptBoundary) > 0) {
                return NotificationReplyCommitProbe.NotCommitted
            }
            if (
                record.direction.equals("sent", ignoreCase = true) &&
                record.plaintext.trim() == body
            ) {
                return if (record.sourceMessageIdHex == null) {
                    NotificationReplyCommitProbe.Indeterminate
                } else {
                    NotificationReplyCommitProbe.Committed
                }
            }
            if (nextAttemptBoundary != null && compareCursor(recordCursor, nextAttemptBoundary) == 0) {
                return NotificationReplyCommitProbe.NotCommitted
            }
        }
        if (!page.hasMoreAfter) return NotificationReplyCommitProbe.NotCommitted
        if (orderedRecords.isEmpty()) return NotificationReplyCommitProbe.Indeterminate

        val nextCursor = orderedRecords.last().cursor()
        if (compareCursor(nextCursor, cursor) <= 0) return NotificationReplyCommitProbe.Indeterminate
        cursor = nextCursor
    }
}

private fun compareCursor(
    left: NotificationReplyRecoveryBoundary,
    right: NotificationReplyRecoveryBoundary,
): Int {
    val timelineComparison = left.timelineAt.compareTo(right.timelineAt)
    return if (timelineComparison != 0) timelineComparison else left.messageIdHex.compareTo(right.messageIdHex)
}

private val MESSAGE_ID = Regex("[0-9a-fA-F]{64}")
private val MAX_TIMELINE_MESSAGE_ID = "f".repeat(64)
private const val RECOVERY_PAGE_LIMIT = 50u
