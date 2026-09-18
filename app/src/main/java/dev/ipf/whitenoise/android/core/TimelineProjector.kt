package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import dev.ipf.whitenoise.android.core.MessageAttachments

/** Complete display projection for an available or unavailable reply target. */
data class TimelineReplyDisplay(
    val sender: String,
    val body: String,
    val mediaKind: ReplyMediaKind = ReplyMediaKind.None,
    val mediaFileName: String? = null,
    val mediaType: String? = null,
    val originalUnavailable: Boolean = false,
    val warning: String? = null,
)

/** Stable media categories used by reply previews across typed and legacy records. */
enum class ReplyMediaKind { None, Photo, Video, Voice, Document }

/** Maps a typed MIME value to the reply-preview media category. */
fun replyMediaKindFromMime(mime: String?): ReplyMediaKind {
    if (mime.isNullOrBlank()) return ReplyMediaKind.None
    return when {
        mime.startsWith("audio/", ignoreCase = true) -> ReplyMediaKind.Voice
        mime.startsWith("image/", ignoreCase = true) -> ReplyMediaKind.Photo
        mime.startsWith("video/", ignoreCase = true) -> ReplyMediaKind.Video
        else -> ReplyMediaKind.Document
    }
}

/** Reply-preview media fallback from MarmotKit outcomes; rejected slots contribute nothing. */
fun acceptedReplyMediaFallback(media: List<MediaAttachmentOutcomeFfi>): MediaPreviewFallback? {
    val accepted = MessageAttachments.acceptedReferences(media)
    return typedReplyMediaFallback(accepted)
}

/** Preserves the first typed attachment's safe reply-preview inputs. */
fun typedReplyMediaFallback(media: List<MediaAttachmentReferenceFfi>): MediaPreviewFallback? =
    media.firstOrNull()?.let { attachment ->
        MediaPreviewFallback(
            filename = attachment.fileName.trim().takeIf { it.isNotEmpty() },
            kind = replyMediaKindFromMime(attachment.mediaType),
            mediaType = attachment.mediaType.trim().takeIf { it.isNotEmpty() },
        )
    }

/** Uses typed media copy only when the reply target has no textual body. */
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

/** Provides a coarse category by finding MIME-family markers in legacy opaque media JSON. */
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
            tags = tagsWithProjectedStructure(record),
            sourceEpoch = record.sourceEpoch,
            retentionSeconds = record.retentionSeconds,
            retentionExpiresAt = record.retentionExpiresAt,
            recordedAt = record.timelineAt,
            receivedAt = record.receivedAt,
        )

    /**
     * MarmotKit 0.10 reports a message's attachments as parsed [TimelineMessageRecordFfi.media]
     * outcomes and its reply target as [TimelineMessageRecordFfi.replyToMessageIdHex], and no longer
     * echoes the event's `imeta`, `e` and `q` tags. The app still classifies a message from those tags,
     * so a captioned photo read as plain text with a stray caption and its caption was dropped, and an
     * echo could not be paired with the reply it confirmed. Give such a record one `imeta` marker per
     * attachment slot, carrying the type and name the attachment views already take from the outcomes,
     * and its reply tags back. A record that carries the tags itself is left exactly as it came.
     */
    internal fun tagsWithProjectedStructure(record: TimelineMessageRecordFfi): List<MessageTagFfi> {
        val tags = record.tags
        val mediaMarkers =
            if (record.media.isEmpty() || tags.any { it.values.firstOrNull() == MessageProjector.ImetaTag }) {
                emptyList()
            } else {
                record.media.map { outcome ->
                    when (outcome) {
                        is MediaAttachmentOutcomeFfi.Accepted ->
                            MessageTagFfi(
                                listOf(
                                    MessageProjector.ImetaTag,
                                    "m ${outcome.reference.mediaType}",
                                    "filename ${outcome.reference.fileName}",
                                ),
                            )
                        is MediaAttachmentOutcomeFfi.Rejected -> MessageTagFfi(listOf(MessageProjector.ImetaTag))
                    }
                }
            }
        val replyTarget = record.replyToMessageIdHex
        val replyTags =
            if (
                replyTarget == null ||
                tags.any { tag ->
                    val name = tag.values.firstOrNull()
                    name == MessageProjector.EventRefTag || name == MessageProjector.QuoteRefTag
                }
            ) {
                emptyList()
            } else {
                listOf(MessageProjector.eventTag(replyTarget), MessageProjector.quoteTag(replyTarget))
            }
        return if (mediaMarkers.isEmpty() && replyTags.isEmpty()) tags else tags + replyTags + mediaMarkers
    }

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

    /** Projects a persisted reply, including a truthful unavailable-target state. */
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
        val mediaFallback = if (preview.deleted) null else acceptedReplyMediaFallback(preview.media)
        return TimelineReplyDisplay(
            sender = preview.sender,
            body = preview.displayBody(copy, mediaFallback),
            mediaKind = replyPreviewMediaKind(preview.deleted, mediaFallback, preview.mediaJson),
            mediaFileName = mediaFallback?.filename,
            mediaType = mediaFallback?.mediaType,
            warning = if (preview.deleted) null else invalidationWarning(preview.invalidationStatus, copy),
        )
    }

    /** Builds the preview shown when the target record itself is still available. */
    fun replyTargetPreview(
        record: TimelineMessageRecordFfi,
        mediaFallback: MediaPreviewFallback? = acceptedReplyMediaFallback(record.media),
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
