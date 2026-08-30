package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi

data class TimelineReplyDisplay(
    val sender: String,
    val body: String,
    val mediaKind: ReplyMediaKind = ReplyMediaKind.None,
    val mediaFileName: String? = null,
    val mediaType: String? = null,
    val originalUnavailable: Boolean = false,
    val warning: String? = null,
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
            mediaType = attachment.mediaType.trim().takeIf { it.isNotEmpty() },
        )
    }

fun replyBodyWithTypedMediaFallback(
    plaintext: String,
    projectedBody: String,
    mediaFallback: MediaPreviewFallback?,
    copy: MessageTextCopy,
): String =
    if (plaintext.isBlank() && mediaFallback != null) {
        mediaFallback.text(copy)
    } else {
        projectedBody
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

private fun replyPreviewMediaKind(
    deleted: Boolean,
    mediaFallback: MediaPreviewFallback?,
    mediaJson: String?,
): ReplyMediaKind =
    if (deleted) {
        ReplyMediaKind.None
    } else {
        mediaFallback?.kind ?: replyMediaKindFromJson(mediaJson)
    }

/**
 * Whether a bubble shows the disappearing-message indicator. An explicit
 * `0` means retention was disabled for this message, so only a positive
 * duration counts.
 */
fun retentionIndicatorVisible(retentionSeconds: ULong?): Boolean = (retentionSeconds ?: 0uL) > 0uL

internal enum class TimelineInvalidationPresentation {
    None,
    PartialVisibility,
    NonCanonicalHistory,

    /**
     * The local publish attempt failed, so delivery is unknown. Content is
     * preserved: claiming the group never got it is a guess, and the tombstone
     * would destroy the user's only copy of the text (#1747).
     */
    UnconfirmedDelivery,
    PersistedFailure,
}

internal fun timelineInvalidationPresentation(status: String?): TimelineInvalidationPresentation =
    when (status) {
        null -> TimelineInvalidationPresentation.None
        "LosingBranch" -> TimelineInvalidationPresentation.PartialVisibility
        "BeyondAnchor",
        "BeyondAppRetention",
        "UndecryptableInCanonicalState",
        -> TimelineInvalidationPresentation.NonCanonicalHistory
        "local_publish_failed" -> TimelineInvalidationPresentation.UnconfirmedDelivery
        // Preserve the established failure UI for future engine reasons until
        // Android has an explicit reason-specific presentation for them.
        else -> TimelineInvalidationPresentation.PersistedFailure
    }

internal fun usesPersistedFailurePresentation(record: TimelineMessageRecordFfi): Boolean =
    !record.deleted &&
        timelineInvalidationPresentation(record.invalidationStatus) == TimelineInvalidationPresentation.PersistedFailure

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
            sourceEpoch = record.sourceEpoch,
            retentionSeconds = record.retentionSeconds,
            retentionExpiresAt = record.retentionExpiresAt,
            recordedAt = record.timelineAt,
            receivedAt = record.receivedAt,
        )

    fun invalidationWarning(
        record: TimelineMessageRecordFfi,
        copy: MessageTextCopy = MessageTextCopy.Default,
    ): String? = if (record.deleted) null else invalidationWarning(record.invalidationStatus, copy)

    private fun invalidationWarning(
        status: String?,
        copy: MessageTextCopy,
    ): String? =
        when (timelineInvalidationPresentation(status)) {
            TimelineInvalidationPresentation.PartialVisibility -> copy.partialVisibility
            TimelineInvalidationPresentation.NonCanonicalHistory -> copy.nonCanonicalHistory
            TimelineInvalidationPresentation.UnconfirmedDelivery -> copy.deliveryNotConfirmed
            TimelineInvalidationPresentation.None,
            TimelineInvalidationPresentation.PersistedFailure,
            -> null
        }

    fun displayBody(
        record: TimelineMessageRecordFfi,
        copy: MessageTextCopy = MessageTextCopy.Default,
    ): String {
        if (record.deleted) return copy.deleted
        if (usesPersistedFailurePresentation(record)) return copy.invalidated
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
        val preview = record.replyPreview
        if (preview == null) {
            return record.replyToMessageIdHex
                ?.takeIf(String::isNotBlank)
                ?.let {
                    TimelineReplyDisplay(
                        sender = "",
                        body = "",
                        originalUnavailable = true,
                    )
                }
        }
        val mediaFallback = if (preview.deleted) null else typedReplyMediaFallback(preview.media)
        return TimelineReplyDisplay(
            sender = preview.sender,
            body = preview.displayBody(copy, mediaFallback),
            mediaKind = replyPreviewMediaKind(preview.deleted, mediaFallback, preview.mediaJson),
            mediaFileName = mediaFallback?.filename,
            mediaType = mediaFallback?.mediaType,
            warning = if (preview.deleted) null else invalidationWarning(preview.invalidationStatus, copy),
        )
    }

    fun replyTargetPreview(
        record: TimelineMessageRecordFfi,
        mediaFallback: MediaPreviewFallback? = typedReplyMediaFallback(record.media),
        copy: MessageTextCopy = MessageTextCopy.Default,
    ): TimelineReplyDisplay {
        val visibleMediaFallback = if (record.deleted) null else mediaFallback
        val projectedBody = displayBody(record, copy)
        return TimelineReplyDisplay(
            sender = record.sender,
            body =
                replyBodyWithTypedMediaFallback(
                    plaintext = record.plaintext,
                    projectedBody = projectedBody,
                    mediaFallback = visibleMediaFallback,
                    copy = copy,
                ),
            mediaKind = replyPreviewMediaKind(record.deleted, visibleMediaFallback, record.mediaJson),
            mediaFileName = visibleMediaFallback?.filename,
            mediaType = visibleMediaFallback?.mediaType,
            warning = invalidationWarning(record, copy),
        )
    }

    fun reactionTallies(
        record: TimelineMessageRecordFfi,
        myAccountId: String?,
    ): List<ReactionTally> =
        reactionTalliesFromEmojiSenders(
            record.reactions.byEmoji.map { it.emoji to it.senders },
            myAccountId,
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
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
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
