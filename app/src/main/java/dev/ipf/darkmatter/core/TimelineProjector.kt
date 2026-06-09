package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi

data class TimelineReplyDisplay(
    val sender: String,
    val body: String,
)

object TimelineProjector {
    fun toAppMessageRecord(record: TimelineMessageRecordFfi): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = record.messageIdHex,
            direction = record.direction,
            groupIdHex = record.groupIdHex,
            sender = record.sender,
            plaintext = record.plaintext,
            contentTokens = record.contentTokens,
            kind = record.kind,
            tags = record.tags,
            recordedAt = record.timelineAt,
            receivedAt = record.receivedAt,
        )

    fun displayBody(
        record: TimelineMessageRecordFfi,
        copy: MessageTextCopy = MessageTextCopy.Default,
    ): String {
        if (record.deleted) return copy.deleted
        // A non-null invalidationStatus means convergence dropped this message
        // onto a losing branch: it never reached the group. The record is kept
        // as a tombstone, so render the "didn't reach the group" copy instead
        // of the original body.
        if (record.invalidationStatus != null) return copy.invalidated
        return projectedBody(
            plaintext = record.plaintext,
            kind = record.kind,
            mediaJson = record.mediaJson,
            agentTextStreamJson = record.agentTextStreamJson,
            fallback = { MessageProjector.displayBody(toAppMessageRecord(record), copy) },
            copy = copy,
        )
    }

    fun replyPreview(
        record: TimelineMessageRecordFfi,
        copy: MessageTextCopy = MessageTextCopy.Default,
    ): TimelineReplyDisplay? {
        val preview = record.replyPreview ?: return null
        return TimelineReplyDisplay(
            sender = preview.sender,
            body = preview.displayBody(copy),
        )
    }

    fun reactionTallies(
        record: TimelineMessageRecordFfi,
        myAccountId: String?,
    ): List<ReactionTally> =
        record.reactions.byEmoji
            .mapNotNull { summary ->
                if (summary.senders.isEmpty()) {
                    null
                } else {
                    ReactionTally(
                        emoji = summary.emoji,
                        count = summary.senders.size,
                        // Case-insensitive: hex account-id casing can drift
                        // between the active account and reaction senders, and
                        // a mismatch would render your own reaction as not-mine.
                        mine = myAccountId != null && summary.senders.any { it.equals(myAccountId, ignoreCase = true) },
                    )
                }
            }.sortedWith(
                compareByDescending<ReactionTally> { it.count }
                    .thenByDescending { it.mine }
                    .thenBy { it.emoji },
            )

    private fun TimelineReplyPreviewFfi.displayBody(copy: MessageTextCopy): String {
        if (deleted) return copy.deleted
        return projectedBody(
            plaintext = plaintext,
            kind = kind,
            mediaJson = mediaJson,
            agentTextStreamJson = agentTextStreamJson,
            fallback = { plaintext },
            copy = copy,
        )
    }

    private fun projectedBody(
        plaintext: String,
        kind: ULong,
        mediaJson: String?,
        agentTextStreamJson: String?,
        fallback: () -> String,
        copy: MessageTextCopy,
    ): String {
        val body = fallback()
        if (body.isNotBlank()) return body
        return when {
            mediaJson != null -> copy.mediaAttachment
            agentTextStreamJson != null -> copy.streamFinished
            kind == 1200uL -> copy.agentStreamStarted
            else -> copy.message
        }
    }
}
