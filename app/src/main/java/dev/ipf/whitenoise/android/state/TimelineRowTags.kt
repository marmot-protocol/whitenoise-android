package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi

private val MessageIdPattern = Regex("[0-9a-fA-F]{64}")

/**
 * One group row's full inner-event tags. Conversation-window rows omit chat tags, and MarmotKit
 * exposes no read by id, so this steps to the row through its neighbours: group cursors are
 * indexed and exclude the cursor row itself. Null when the row is gone or not yet stored.
 */
internal fun MarmotInterface.timelineRowTags(
    account: String,
    groupIdHex: String,
    messageIdHex: String,
): List<MessageTagFfi>? {
    if (!MessageIdPattern.matches(messageIdHex)) {
        return null
    }

    fun adjacent(cursor: TimelineCursor?): TimelineMessageRecordFfi? {
        val query =
            TimelineMessageQueryFfi(
                groupIdHex = groupIdHex,
                search = null,
                // Group cursors are ordered by message id; the timestamp only has to be present.
                before = 0uL.takeIf { cursor is TimelineCursor.Before },
                beforeMessageId = (cursor as? TimelineCursor.Before)?.messageIdHex,
                after = 0uL.takeIf { cursor is TimelineCursor.After },
                afterMessageId = (cursor as? TimelineCursor.After)?.messageIdHex,
                limit = 1u,
            )
        val rows = timelineMessages(account, query).messages
        return if (cursor is TimelineCursor.After) rows.firstOrNull() else rows.lastOrNull()
    }

    val row =
        try {
            val previous = adjacent(TimelineCursor.Before(messageIdHex))
            if (previous != null) {
                adjacent(TimelineCursor.After(previous.messageIdHex))
            } else {
                val next = adjacent(TimelineCursor.After(messageIdHex))
                adjacent(next?.let { TimelineCursor.Before(it.messageIdHex) })
            }
        } catch (_: MarmotKitException) {
            null
        }
    return row?.takeIf { it.messageIdHex.equals(messageIdHex, ignoreCase = true) }?.tags
}

private sealed interface TimelineCursor {
    val messageIdHex: String

    data class Before(
        override val messageIdHex: String,
    ) : TimelineCursor

    data class After(
        override val messageIdHex: String,
    ) : TimelineCursor
}
