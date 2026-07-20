package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi

data class TimelineReplyDisplay(
    val sender: String,
    val body: String,
    val mediaKind: ReplyMediaKind = ReplyMediaKind.None,
)

enum class ReplyMediaKind { None, Photo, Video, Voice, Document }

fun replyMediaKindFromMime(mime: String?): ReplyMediaKind {
    if (mime.isNullOrBlank()) return ReplyMediaKind.None
    return when {
        mime.startsWith("audio/", ignoreCase = true) -> ReplyMediaKind.Voice
        mime.startsWith("image/", ignoreCase = true) -> ReplyMediaKind.Photo
        mime.startsWith("video/", ignoreCase = true) -> ReplyMediaKind.Video
        else -> ReplyMediaKind.Document
    }
}

fun typedReplyMediaFallback(media: List<MediaAttachmentReferenceFfi>): MediaPreviewFallback? =
    media.firstOrNull()?.let { attachment ->
        MediaPreviewFallback(
            filename = attachment.fileName.trim().takeIf { it.isNotEmpty() },
            kind = replyMediaKindFromMime(attachment.mediaType),
        )
    }

// Heuristic on the FFI's reply preview mediaJson (opaque JSON; just looks
// for the MIME tree prefix). Cheap and good enough for "what icon to show".
fun replyMediaKindFromJson(mediaJson: String?): ReplyMediaKind {
    if (mediaJson.isNullOrBlank()) return ReplyMediaKind.None
    val lower = localeInvariantFold(mediaJson)
    return when {
        "audio/" in lower -> ReplyMediaKind.Voice
        "image/" in lower -> ReplyMediaKind.Photo
        "video/" in lower -> ReplyMediaKind.Video
        else -> ReplyMediaKind.Document
    }
}

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
        val mediaFallback = typedReplyMediaFallback(preview.media)
        return TimelineReplyDisplay(
            sender = preview.sender,
            body = preview.displayBody(copy, mediaFallback),
            mediaKind = mediaFallback?.kind ?: ReplyMediaKind.None,
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

    private fun TimelineReplyPreviewFfi.displayBody(
        copy: MessageTextCopy,
        mediaFallback: MediaPreviewFallback?,
    ): String {
        if (deleted) return copy.deleted
        return projectedBody(
            plaintext = plaintext,
            kind = kind,
            mediaJson = mediaJson,
            agentTextStreamJson = agentTextStreamJson,
            fallback = { MessageProjector.displayBody(toAppMessageRecord(), copy) },
            mediaFallback = mediaFallback,
            copy = copy,
        )
    }

    private fun TimelineReplyPreviewFfi.toAppMessageRecord(): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = messageIdHex,
            direction = "received",
            groupIdHex = "",
            sender = sender,
            plaintext = plaintext,
            contentTokens = contentTokens,
            kind = kind,
            tags = emptyList(),
            recordedAt = 0uL,
            receivedAt = 0uL,
        )

    private fun projectedBody(
        plaintext: String,
        kind: ULong,
        mediaJson: String?,
        agentTextStreamJson: String?,
        fallback: () -> String,
        mediaFallback: MediaPreviewFallback? = null,
        copy: MessageTextCopy,
    ): String {
        val body = fallback()
        if (body.isNotBlank()) return body
        return when {
            mediaFallback != null -> mediaFallback.text(copy)
            mediaJson != null -> copy.mediaLabel(replyMediaKindFromJson(mediaJson))
            agentTextStreamJson != null -> copy.streamFinished
            kind == 1200uL -> copy.agentStreamStarted
            else -> copy.message
        }
    }
}
