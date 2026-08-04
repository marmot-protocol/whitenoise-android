package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListAttachmentKindFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.media.MediaReferenceSupport

data class ReactionTally(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

/**
 * Shared final stage for reaction tallies, single-sourced so the ordering and
 * mine/count semantics cannot drift between the raw-record path
 * ([MessageProjector.reactionTallies]) and the engine-summary path
 * ([TimelineProjector.reactionTallies]): emoji groups with senders become
 * tallies sorted by count desc, own reaction first among equals, then emoji
 * for stability.
 */
internal fun reactionTalliesFromEmojiSenders(
    emojiSenders: List<Pair<String, Collection<String>>>,
    myAccountId: String?,
): List<ReactionTally> =
    emojiSenders
        .mapNotNull { (emoji, senders) ->
            if (senders.isEmpty()) {
                null
            } else {
                ReactionTally(
                    emoji = emoji,
                    count = senders.size,
                    // Case-insensitive: hex account-id casing can drift between
                    // the active account and reaction senders, and a mismatch
                    // would render your own reaction as not-mine.
                    mine = myAccountId != null && senders.any { it.equals(myAccountId, ignoreCase = true) },
                )
            }
        }.sortedWith(
            compareByDescending<ReactionTally> { it.count }
                .thenByDescending { it.mine }
                .thenBy { it.emoji },
        )

data class MessageTextCopy(
    val reactedFormat: String,
    val reactionFallback: String,
    val deleted: String,
    val invalidated: String,
    val partialVisibility: String,
    val nonCanonicalHistory: String,
    val deliveryNotConfirmed: String,
    val agentStreamStarted: String,
    val streamFinished: String,
    val mediaAttachment: String,
    val mediaPhoto: String = "Photo",
    val mediaVideo: String = "Video",
    val mediaVoice: String = "Voice message",
    val mediaDocument: String = "File",
    val mediaAlbum: String = "Album",
    val mediaCountedFormat: String = "%1\$s (%2\$d)",
    val message: String,
    val groupSystem: GroupSystemCopy = GroupSystemCopy.Default,
) {
    fun reacted(value: String): String = String.format(reactedFormat, value)

    fun mediaLabel(kind: ReplyMediaKind): String =
        when (kind) {
            ReplyMediaKind.Photo -> mediaPhoto
            ReplyMediaKind.Video -> mediaVideo
            ReplyMediaKind.Voice -> mediaVoice
            ReplyMediaKind.Document -> mediaDocument
            ReplyMediaKind.None -> mediaAttachment
        }

    /** Preview label for a projected last-message attachment set. */
    fun attachmentLabel(
        kind: ChatListAttachmentKindFfi,
        count: UInt,
    ): String {
        val label =
            when (kind) {
                ChatListAttachmentKindFfi.PHOTO -> mediaPhoto
                ChatListAttachmentKindFfi.VIDEO -> mediaVideo
                ChatListAttachmentKindFfi.AUDIO -> mediaVoice
                ChatListAttachmentKindFfi.FILE -> mediaDocument
                ChatListAttachmentKindFfi.MIXED -> mediaAlbum
            }
        return if (count > 1u) String.format(mediaCountedFormat, label, count.toLong()) else label
    }

    companion object {
        val Default =
            MessageTextCopy(
                reactedFormat = "Reacted %1\$s",
                reactionFallback = "to a message",
                deleted = "Deleted a message",
                invalidated = "Didn't reach the group",
                partialVisibility = "May not be visible to everyone",
                nonCanonicalHistory = "Not confirmed in the group's current history",
                deliveryNotConfirmed = "Delivery not confirmed",
                agentStreamStarted = "Agent stream started",
                streamFinished = "Stream finished",
                mediaAttachment = "Media attachment",
                mediaPhoto = "Photo",
                mediaVideo = "Video",
                mediaVoice = "Voice message",
                mediaDocument = "File",
                message = "Message",
            )
    }
}

data class MediaPreviewFallback(
    val filename: String? = null,
    val kind: ReplyMediaKind = ReplyMediaKind.None,
) {
    // Photo/Video/Voice filenames are auto-generated (voice-<ms>ms.m4a, swapped
    // numeric .jpg) and never user-meaningful — prefer the typed label. Only a
    // document filename is worth showing; None keeps the filename fallback.
    fun text(copy: MessageTextCopy): String =
        when (kind) {
            ReplyMediaKind.Photo,
            ReplyMediaKind.Video,
            ReplyMediaKind.Voice,
            -> copy.mediaLabel(kind)
            ReplyMediaKind.Document,
            ReplyMediaKind.None,
            -> filename ?: copy.mediaLabel(kind)
        }
}

object MessageProjector {
    private val KindDelete = 5uL
    private val KindReaction = 7uL
    private val KindChat = 9uL
    private val KindEdit = 1009uL
    private val KindAgentStreamStart = 1200uL
    private const val KindAgentOperation = 1202uL
    private val KindGroupSystem = 1210uL

    private const val EventRefTag = "e"
    private const val QuoteRefTag = "q"
    private const val ImetaTag = "imeta"
    private const val PendingMediaTag = "_media_pending"
    private const val StreamTag = "stream"
    private const val StreamStartTag = "stream-start"
    private const val StreamHashTag = "stream-hash"

    fun displayBody(
        message: AppMessageRecordFfi,
        copy: MessageTextCopy = MessageTextCopy.Default,
    ): String =
        when {
            isReaction(message) -> copy.reacted(message.plaintext.ifBlank { copy.reactionFallback })
            isDelete(message) -> copy.deleted
            isStreamStart(message) -> message.plaintext.ifBlank { copy.agentStreamStarted }
            // Never the raw JSON: kind-1210 content must not render as a chat
            // body. The conversation row builds the name-resolved summary;
            // this name-free form covers reply previews and copy-text.
            isGroupSystem(message) -> GroupSystemEvents.previewText(message.plaintext, copy.groupSystem)
            isMedia(message) -> mediaBodyText(message, copy)
            else -> message.plaintext
        }

    fun previewText(
        message: AppMessageRecordFfi?,
        copy: MessageTextCopy = MessageTextCopy.Default,
        empty: String = "No messages yet",
    ): String {
        if (message == null) return empty
        return when {
            isReaction(message) -> copy.reacted(message.plaintext.ifBlank { copy.reactionFallback })
            isDelete(message) -> copy.deleted
            isStreamStart(message) -> copy.agentStreamStarted
            isGroupSystem(message) -> GroupSystemEvents.previewText(message.plaintext, copy.groupSystem)
            isStreamFinal(message) -> message.plaintext.ifBlank { copy.streamFinished }
            isMedia(message) -> mediaBodyText(message, copy)
            else -> message.plaintext.ifBlank { copy.message }
        }
    }

    fun reactionTallies(
        records: List<AppMessageRecordFfi>,
        targetMessageId: String,
        myAccountId: String?,
    ): List<ReactionTally> {
        // Track each surviving reaction event individually. Presence is derived
        // from these records at the end, not maintained incrementally: a sender
        // can emit several distinct events for the same emoji (replay,
        // convergence, double-tap), so retracting one must not erase the others.
        val reactionById = linkedMapOf<String, ReactionRecord>()
        records
            .asSequence()
            .sortedBy { it.recordedAt }
            .forEach { record ->
                when {
                    isReaction(record) -> {
                        val target = firstEventRef(record) ?: return@forEach
                        val emoji = record.plaintext
                        if (target != targetMessageId || emoji.isBlank()) return@forEach
                        // Senders are stored lowercased: hex account-id casing can
                        // drift between events, and a case-variant duplicate would
                        // double-count or break the remove path below.
                        reactionById[record.messageIdHex] = ReactionRecord(target, emoji, record.sender.lowercase())
                    }
                    isDelete(record) -> {
                        val deleter = record.sender.lowercase()
                        for (deletedId in deletedTargetMessageIds(record)) {
                            val deletedReaction = reactionById[deletedId]
                            if (deletedReaction != null && deletedReaction.targetMessageId == targetMessageId) {
                                // Only the reaction's own author may retract it; ignore a
                                // forged delete from another account trying to hide it.
                                // Drops just this event — a same-sender, same-emoji
                                // duplicate stays live so the tally is not erased.
                                if (deleter == deletedReaction.sender) {
                                    reactionById.remove(deletedId)
                                }
                            } else if (deletedId == targetMessageId) {
                                // Deleting the reacted-to message retracts every reaction
                                // the deleter authored on it (across all emoji).
                                reactionById.values.removeAll { it.sender == deleter }
                            }
                        }
                    }
                }
            }

        // Group the surviving events by emoji and count distinct senders. Using
        // reactionById's insertion order (sorted by recordedAt above) keeps the
        // per-emoji order stable for the emoji tiebreaker in the final sort.
        val sendersByEmoji = linkedMapOf<String, MutableSet<String>>()
        val displayEmojiByNormalized = linkedMapOf<String, String>()
        for (reaction in reactionById.values) {
            val emoji = normalizeReactionEmoji(reaction.emoji)
            val displayEmoji = displayEmojiByNormalized[emoji]
            if (displayEmoji == null || '\uFE0F' in reaction.emoji && '\uFE0F' !in displayEmoji) {
                displayEmojiByNormalized[emoji] = reaction.emoji
            }
            sendersByEmoji.getOrPut(emoji) { linkedSetOf() }.add(reaction.sender)
        }

        return reactionTalliesFromEmojiSenders(
            sendersByEmoji.map { (emoji, senders) -> displayEmojiByNormalized.getValue(emoji) to senders },
            myAccountId,
        )
    }

    internal fun normalizeReactionEmoji(emoji: String): String =
        emoji
            .filterNot { it == '\uFE0E' || it == '\uFE0F' }
            .ifBlank { emoji }

    fun isMine(
        message: AppMessageRecordFfi,
        myAccountId: String?,
    ): Boolean =
        message.direction == "sent" ||
            (myAccountId != null && message.sender.equals(myAccountId, ignoreCase = true))

    fun isDeleted(
        messageIdHex: String,
        deletedMessageIds: Set<String>,
    ): Boolean = messageIdHex.isNotEmpty() && deletedMessageIds.contains(messageIdHex)

    fun isGroupSystem(message: AppMessageRecordFfi): Boolean = isGroupSystemKind(message.kind)

    fun isGroupSystemKind(kind: ULong): Boolean = kind == KindGroupSystem

    /**
     * True for a regular chat-message kind (kind 9). Narrower than
     * [rendersRawBodyPreview]: not every kind whose chat-list preview is the
     * raw body is a kind-9 chat message (e.g. kind-1 legacy notes and
     * kind-1209 agent-stream finals also fall through to the verbatim-body
     * arm). Use [rendersRawBodyPreview] when the question is "is the displayed
     * preview the raw plaintext?"; reserve this for "is this specifically a
     * kind-9 chat record?".
     */
    fun isChatKind(kind: ULong): Boolean = kind == KindChat

    /**
     * True when a chat-list row's displayed preview line is the message's raw
     * plaintext itself, rather than derived/fallback copy. This is the exact
     * complement of the special-cased arms in
     * [ChatListItem.projectedPreviewText]: edit records (kind-1009),
     * agent-stream starts (kind-1200) and group-system events (kind-1210)
     * surface synthetic copy, so they are excluded; every other kind falls
     * through to the verbatim `preview.plaintext` arm. Naming the predicate
     * here keeps the markdown parse gate
     * ([Controllers.chatRowPreviewMarkdownSource]) tied to the same set of
     * rows the preview renderer would show verbatim, so it covers kind-1
     * (legacy note), kind-9 (chat), kind-1209 (agent-stream final) and any
     * future body kind without re-enumerating them (issue #577).
     *
     * Callers still apply the deleted/blank guards separately, matching the
     * `preview.deleted` and `plaintext.isNotBlank()` checks the renderer makes.
     */
    fun rendersRawBodyPreview(kind: ULong): Boolean =
        kind != KindEdit &&
            kind != KindAgentStreamStart &&
            !isGroupSystemKind(kind)

    fun isReaction(message: AppMessageRecordFfi): Boolean = message.kind == KindReaction

    fun isDelete(message: AppMessageRecordFfi): Boolean = message.kind == KindDelete

    fun isEdit(message: AppMessageRecordFfi): Boolean = message.kind == KindEdit

    /**
     * For a kind-1009 edit record, the target message id from its single
     * `e` tag, or null when malformed/missing. Use this to route an edit
     * back to the message it replaces; never derive an edit target from
     * any other tag — spec wire format pins the reference to `e`.
     */
    fun editTargetMessageId(message: AppMessageRecordFfi): String? = if (message.kind == KindEdit) tagValues(message, EventRefTag).firstOrNull() else null

    fun isStreamStart(message: AppMessageRecordFfi): Boolean = message.kind == KindAgentStreamStart

    fun isAgentOperation(message: AppMessageRecordFfi): Boolean = message.kind == KindAgentOperation

    fun isStreamFinal(message: AppMessageRecordFfi): Boolean =
        message.kind == KindChat &&
            tagValue(message, StreamTag) != null &&
            (tagValue(message, StreamStartTag) != null || tagValue(message, StreamHashTag) != null)

    fun streamId(message: AppMessageRecordFfi): String? = tagValue(message, StreamTag)

    /**
     * Normalize a requested forward fan-out target list into the distinct,
     * non-blank group ids to send into, preserving first-seen order.
     *
     * Forwarding the same message twice into one chat (a double-tap in the
     * picker, or a duplicate id from the caller) must produce exactly one send;
     * blank ids are dropped defensively. Pure list logic, factored out of
     * [WhiteNoiseAppState.forwardText] so the de-dupe/blank rules are unit
     * testable without the FFI send path.
     */
    fun normalizeForwardTargets(targetGroupIds: List<String>): List<String> = targetGroupIds.filter { it.isNotBlank() }.distinct()

    /**
     * Whether [message] is a plain user-authored text message that v1 forwarding
     * may carry into another chat (issue #390, scope: text only).
     *
     * Forwarding re-sends the raw text body verbatim into the target group, so
     * the predicate must exclude every record whose user-facing rendering is a
     * *synthetic surrogate* rather than the original text — otherwise a media,
     * reaction, delete, agent-stream, or group-system bubble would be forwarded
     * as misleading fallback copy (e.g. a filename, "Reacted 👍", or a generated
     * summary) even though those kinds are out of v1 scope.
     *
     * Forwardable iff the record is a kind-9 chat message that is NOT confirmed
     * or pending media, NOT an agent-stream message (no `stream` tag), and carries
     * non-blank text. Reactions (kind-7), deletes (kind-5),
     * agent-stream starts (kind-1200), group-system events (kind-1210) and edit
     * records (kind-1009) are all non-kind-9 and therefore excluded by the
     * kind check; the tag checks then strip the kind-9 media/stream variants.
     */
    fun isForwardableText(message: AppMessageRecordFfi): Boolean =
        message.kind == KindChat &&
            !isMedia(message) &&
            !isPendingMedia(message) &&
            streamId(message) == null &&
            message.plaintext.isNotBlank()

    /**
     * The raw text payload to forward for [message], preferring the latest
     * edited body [editedText] over the original plaintext when an edit overlay
     * is present (so forwarding a since-edited message carries the current text,
     * matching what the bubble shows). Returns null when the message is not a
     * forwardable text record or the resolved body is blank — callers must treat
     * null as "do not forward".
     *
     * The returned string is the verbatim message text: it carries neither the
     * original sender's pubkey nor any source-group identifier, so a forward
     * never leaks cross-group attribution (issue #390 privacy notes). Never the
     * display fallback copy produced by [displayBody]/[previewText], which would
     * substitute a synthetic surrogate for non-text records.
     */
    fun forwardableText(
        message: AppMessageRecordFfi,
        editedText: String? = null,
    ): String? {
        if (!isForwardableText(message)) return null
        val body = editedText?.takeIf { it.isNotBlank() } ?: message.plaintext
        return body.takeIf { it.isNotBlank() }
    }

    /**
     * Validates a forwarded text batch without rewriting user-authored content.
     * A mixed valid/invalid batch is rejected atomically so no selected message
     * is silently omitted downstream.
     */
    fun validatedForwardTextBodies(texts: List<String?>): List<String> {
        if (texts.isEmpty()) return emptyList()
        return texts.map { text ->
            text?.takeIf(String::isNotBlank) ?: return emptyList()
        }
    }

    /**
     * Plain text copied by multi-select. Unlike forwarding, media records are
     * accepted when they carry a user-authored caption; filename and media-type
     * fallbacks are never copied. Reactions, system events, and agent streams
     * are not user-authored chat text and are skipped.
     */
    fun copyableText(
        message: AppMessageRecordFfi,
        editedText: String? = null,
    ): String? {
        if (message.kind != KindChat || streamId(message) != null) return null
        val editedBody = editedText?.takeIf { it.isNotBlank() }
        val body = editedBody ?: message.plaintext
        if (editedBody == null && isPendingMedia(message) && body == pendingMediaPlaceholder(message)) return null
        return body.takeIf { it.isNotBlank() }
    }

    /**
     * User-authored caption shown with confirmed or pending media. Pending rows
     * reuse [AppMessageRecordFfi.plaintext] for a synthetic attachment label
     * when no caption was entered; that transport placeholder must not become a
     * visible caption.
     */
    fun mediaCaption(
        message: AppMessageRecordFfi,
        body: String = message.plaintext,
    ): String? {
        val pending = isPendingMedia(message)
        return body.takeIf {
            (isMedia(message) || pending) &&
                it.isNotBlank() &&
                (!pending || it != pendingMediaPlaceholder(message))
        }
    }

    /** Read-aloud is deliberately available for exactly the text Copy exposes. */
    fun canSpeak(
        message: AppMessageRecordFfi,
        editedText: String? = null,
    ): Boolean = copyableText(message, editedText) != null

    fun replyTargetMessageId(message: AppMessageRecordFfi): String? = tagValue(message, QuoteRefTag)

    /** Kind-7 reaction events reference the reacted-to message via an `e` tag. */
    fun reactedToMessageId(message: AppMessageRecordFfi): String? = if (isReaction(message)) tagValue(message, EventRefTag) else null

    fun deletedTargetMessageIds(message: AppMessageRecordFfi): List<String> {
        if (!isDelete(message)) return emptyList()
        return tagValues(message, EventRefTag)
    }

    fun eventTag(targetMessageId: String): MessageTagFfi = MessageTagFfi(listOf(EventRefTag, targetMessageId))

    fun quoteTag(targetMessageId: String): MessageTagFfi = MessageTagFfi(listOf(QuoteRefTag, targetMessageId))

    fun streamTag(streamId: String): MessageTagFfi = MessageTagFfi(listOf(StreamTag, streamId))

    private fun isMedia(message: AppMessageRecordFfi): Boolean = message.kind == KindChat && message.tags.any { it.values.firstOrNull() == ImetaTag }

    private fun isPendingMedia(message: AppMessageRecordFfi): Boolean =
        message.kind == KindChat && message.tags.any { it.values.firstOrNull() == PendingMediaTag }

    private fun pendingMediaPlaceholder(message: AppMessageRecordFfi): String? {
        val pendingTags = message.tags.filter { it.values.firstOrNull() == PendingMediaTag }
        if (pendingTags.isEmpty()) return null
        val label =
            if (pendingTags.size == 1) {
                pendingTags.single().values.getOrNull(1) ?: return null
            } else {
                "${pendingTags.size} attachments"
            }
        return "📎 $label"
    }

    private fun mediaBodyText(
        message: AppMessageRecordFfi,
        copy: MessageTextCopy,
    ): String =
        message.plaintext.takeIf { it.isNotBlank() }
            ?: mediaPreviewFallback(message)?.text(copy)
            ?: copy.mediaAttachment

    fun mediaPreviewFallback(message: AppMessageRecordFfi): MediaPreviewFallback? =
        mediaPreviewFallback(
            message = message,
            representative =
                MediaReferenceSupport.parseImetaTag(
                    tags = message.tags,
                    sourceEpoch = message.sourceEpoch ?: 0uL,
                ),
        )

    internal fun mediaPreviewFallback(
        message: AppMessageRecordFfi,
        representative: MediaAttachmentReferenceFfi?,
    ): MediaPreviewFallback? =
        if (isMedia(message)) {
            // The representative attachment comes from the same per-tag parse
            // the renderer uses; when no tag validates, fields are read from
            // the FIRST imeta tag only. Either way a single tag defines the
            // label — a flattened scan could mix `m` from one album tag with
            // `filename` from another and describe an attachment that isn't
            // the one rendered.
            if (representative != null) {
                MediaPreviewFallback(
                    filename = representative.fileName.trim().takeIf { it.isNotEmpty() },
                    kind = replyMediaKindFromMime(representative.mediaType),
                )
            } else {
                MediaPreviewFallback(
                    filename = firstImetaTagField(message, "filename"),
                    kind = replyMediaKindFromMime(firstImetaTagField(message, "m")),
                )
            }
        } else {
            null
        }

    // Coarse media classification for a captionless record, so a surface that
    // shows a type-aware label (e.g. a notification body) can say "sent a
    // picture" rather than a generic placeholder. Classified from the same
    // single tag [mediaPreviewFallback] reads. None for non-media messages.
    fun mediaKind(message: AppMessageRecordFfi): ReplyMediaKind =
        mediaKind(
            message = message,
            representative =
                MediaReferenceSupport.parseImetaTag(
                    tags = message.tags,
                    sourceEpoch = message.sourceEpoch ?: 0uL,
                ),
        )

    internal fun mediaKind(
        message: AppMessageRecordFfi,
        representative: MediaAttachmentReferenceFfi?,
    ): ReplyMediaKind =
        if (isMedia(message)) {
            replyMediaKindFromMime(
                representative?.mediaType
                    ?: firstImetaTagField(message, "m"),
            )
        } else {
            ReplyMediaKind.None
        }

    private fun firstImetaTagField(
        message: AppMessageRecordFfi,
        fieldName: String,
    ): String? {
        val prefix = "$fieldName "
        return message.tags
            .firstOrNull { it.values.firstOrNull() == ImetaTag }
            ?.values
            ?.drop(1)
            ?.firstNotNullOfOrNull { value ->
                value
                    .removePrefix(prefix)
                    .takeIf { value.startsWith(prefix) && it.isNotBlank() }
            }
    }

    private fun firstEventRef(message: AppMessageRecordFfi): String? = tagValue(message, EventRefTag)

    private fun tagValue(
        message: AppMessageRecordFfi,
        name: String,
    ): String? =
        message.tags
            .firstOrNull { it.values.firstOrNull() == name }
            ?.values
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun tagValues(
        message: AppMessageRecordFfi,
        name: String,
    ): List<String> =
        message.tags
            .filter { it.values.firstOrNull() == name }
            .mapNotNull { it.values.getOrNull(1)?.takeIf { value -> value.isNotBlank() } }

    private data class ReactionRecord(
        val targetMessageId: String,
        val emoji: String,
        val sender: String,
    )
}
