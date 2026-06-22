package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MessageTagFfi

data class ReactionTally(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

data class MessageTextCopy(
    val reactedFormat: String,
    val reactionFallback: String,
    val deleted: String,
    val invalidated: String,
    val agentStreamStarted: String,
    val streamFinished: String,
    val mediaAttachment: String,
    val message: String,
    val groupSystem: GroupSystemCopy = GroupSystemCopy.Default,
) {
    fun reacted(value: String): String = String.format(reactedFormat, value)

    companion object {
        val Default =
            MessageTextCopy(
                reactedFormat = "Reacted %1\$s",
                reactionFallback = "to a message",
                deleted = "Deleted a message",
                invalidated = "Didn't reach the group",
                agentStreamStarted = "Agent stream started",
                streamFinished = "Stream finished",
                mediaAttachment = "Media attachment",
                message = "Message",
            )
    }
}

object MessageProjector {
    private val KindDelete = 5uL
    private val KindReaction = 7uL
    private val KindChat = 9uL
    private val KindEdit = 1009uL
    private val KindAgentStreamStart = 1200uL
    private val KindGroupSystem = 1210uL

    private const val EventRefTag = "e"
    private const val QuoteRefTag = "q"
    private const val ImetaTag = "imeta"
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
            isMedia(message) ->
                message.plaintext.takeIf { it.isNotBlank() }
                    ?: imetaField(message, "filename")
                    ?: copy.mediaAttachment
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
            isMedia(message) ->
                message.plaintext.takeIf { it.isNotBlank() }
                    ?: imetaField(message, "filename")
                    ?: copy.mediaAttachment
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
        for (reaction in reactionById.values) {
            sendersByEmoji.getOrPut(reaction.emoji) { linkedSetOf() }.add(reaction.sender)
        }

        return sendersByEmoji
            .mapNotNull { (emoji, senders) ->
                if (senders.isEmpty()) {
                    null
                } else {
                    ReactionTally(
                        emoji = emoji,
                        count = senders.size,
                        // Case-insensitive: see TimelineProjector.reactionTallies.
                        mine = myAccountId != null && senders.contains(myAccountId.lowercase()),
                    )
                }
            }.sortedWith(
                compareByDescending<ReactionTally> { it.count }
                    .thenByDescending { it.mine }
                    .thenBy { it.emoji },
            )
    }

    fun isMine(
        message: AppMessageRecordFfi,
        myAccountId: String?,
    ): Boolean = message.direction == "sent" || (myAccountId != null && message.sender == myAccountId)

    fun isDeleted(
        messageIdHex: String,
        deletedMessageIds: Set<String>,
    ): Boolean = messageIdHex.isNotEmpty() && deletedMessageIds.contains(messageIdHex)

    fun isGroupSystem(message: AppMessageRecordFfi): Boolean = isGroupSystemKind(message.kind)

    fun isGroupSystemKind(kind: ULong): Boolean = kind == KindGroupSystem

    /**
     * True for a regular chat-message kind (kind 9) — the only kind whose
     * chat-list preview line is the message body itself. Synthetic/fallback
     * rows (edits, agent-stream starts, group-system events, deletes) render
     * derived copy instead, so callers gating "is the displayed preview the
     * raw plaintext?" must mirror this. See [Controllers.chatRowPreviewMarkdownSource].
     */
    fun isChatKind(kind: ULong): Boolean = kind == KindChat

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
     * [DarkMatterAppState.forwardText] so the de-dupe/blank rules are unit
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
     * Forwardable iff the record is a kind-9 chat message that is NOT media
     * (no `imeta` attachment), NOT an agent-stream message (no `stream` tag),
     * and carries non-blank text. Reactions (kind-7), deletes (kind-5),
     * agent-stream starts (kind-1200), group-system events (kind-1210) and edit
     * records (kind-1009) are all non-kind-9 and therefore excluded by the
     * kind check; the tag checks then strip the kind-9 media/stream variants.
     */
    fun isForwardableText(message: AppMessageRecordFfi): Boolean =
        message.kind == KindChat &&
            !isMedia(message) &&
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

    fun replyTargetMessageId(message: AppMessageRecordFfi): String? = tagValue(message, QuoteRefTag)

    fun deletedTargetMessageIds(message: AppMessageRecordFfi): List<String> {
        if (!isDelete(message)) return emptyList()
        return tagValues(message, EventRefTag)
    }

    fun eventTag(targetMessageId: String): MessageTagFfi = MessageTagFfi(listOf(EventRefTag, targetMessageId))

    fun quoteTag(targetMessageId: String): MessageTagFfi = MessageTagFfi(listOf(QuoteRefTag, targetMessageId))

    fun streamTag(streamId: String): MessageTagFfi = MessageTagFfi(listOf(StreamTag, streamId))

    private fun isMedia(message: AppMessageRecordFfi): Boolean = message.kind == KindChat && message.tags.any { it.values.firstOrNull() == ImetaTag }

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

    private fun imetaField(
        message: AppMessageRecordFfi,
        fieldName: String,
    ): String? {
        val prefix = "$fieldName "
        return message.tags
            .asSequence()
            .filter { it.values.firstOrNull() == ImetaTag }
            .flatMap { it.values.drop(1).asSequence() }
            .firstNotNullOfOrNull { value ->
                value
                    .removePrefix(prefix)
                    .takeIf { value.startsWith(prefix) && it.isNotBlank() }
            }
    }

    private data class ReactionRecord(
        val targetMessageId: String,
        val emoji: String,
        val sender: String,
    )
}
