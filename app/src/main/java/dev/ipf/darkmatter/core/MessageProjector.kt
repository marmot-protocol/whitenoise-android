package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MessageTagFfi

data class ReactionTally(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

object MessageProjector {
    private val KindDelete = 5uL
    private val KindReaction = 7uL
    private val KindChat = 9uL
    private val KindAgentStreamStart = 1200uL

    private const val EventRefTag = "e"
    private const val QuoteRefTag = "q"
    private const val ImetaTag = "imeta"
    private const val StreamTag = "stream"
    private const val StreamStartTag = "stream-start"
    private const val StreamHashTag = "stream-hash"

    fun displayBody(message: AppMessageRecordFfi): String {
        return when {
            isReaction(message) -> "Reacted ${message.plaintext.ifBlank { "to a message" }}"
            isDelete(message) -> "Deleted a message"
            isStreamStart(message) -> message.plaintext.ifBlank { "Agent stream started" }
            isMedia(message) -> message.plaintext.takeIf { it.isNotBlank() }
                ?: imetaField(message, "filename")
                ?: "Media attachment"
            else -> message.plaintext
        }
    }

    fun previewText(message: AppMessageRecordFfi?): String {
        if (message == null) return "No messages yet"
        return when {
            isReaction(message) -> "Reacted ${message.plaintext.ifBlank { "to a message" }}"
            isDelete(message) -> "Deleted a message"
            isStreamStart(message) -> "Agent stream started"
            isStreamFinal(message) -> message.plaintext.ifBlank { "Stream finished" }
            isMedia(message) -> message.plaintext.takeIf { it.isNotBlank() }
                ?: imetaField(message, "filename")
                ?: "Media attachment"
            else -> message.plaintext.ifBlank { "Message" }
        }
    }

    fun reactionTallies(
        records: List<AppMessageRecordFfi>,
        targetMessageId: String,
        myAccountId: String?,
    ): List<ReactionTally> {
        val sendersByEmoji = linkedMapOf<String, MutableSet<String>>()
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
                        reactionById[record.messageIdHex] = ReactionRecord(target, emoji, record.sender)
                        sendersByEmoji.getOrPut(emoji) { linkedSetOf() }.add(record.sender)
                    }
                    isDelete(record) -> {
                        for (deletedId in deletedTargetMessageIds(record)) {
                            val deletedReaction = reactionById.remove(deletedId)
                            if (deletedReaction != null && deletedReaction.targetMessageId == targetMessageId) {
                                sendersByEmoji[deletedReaction.emoji]?.remove(deletedReaction.sender)
                            } else if (deletedId == targetMessageId) {
                                for (senders in sendersByEmoji.values) {
                                    senders.remove(record.sender)
                                }
                            }
                        }
                    }
                }
            }

        return sendersByEmoji
            .mapNotNull { (emoji, senders) ->
                if (senders.isEmpty()) null
                else ReactionTally(
                    emoji = emoji,
                    count = senders.size,
                    mine = myAccountId != null && senders.contains(myAccountId),
                )
            }
            .sortedWith(
                compareByDescending<ReactionTally> { it.count }
                    .thenByDescending { it.mine }
                    .thenBy { it.emoji },
            )
    }

    fun isMine(message: AppMessageRecordFfi, myAccountId: String?): Boolean {
        return message.direction == "sent" || (myAccountId != null && message.sender == myAccountId)
    }

    fun isDeleted(messageIdHex: String, deletedMessageIds: Set<String>): Boolean {
        return messageIdHex.isNotEmpty() && deletedMessageIds.contains(messageIdHex)
    }

    fun isReaction(message: AppMessageRecordFfi): Boolean = message.kind == KindReaction

    fun isDelete(message: AppMessageRecordFfi): Boolean = message.kind == KindDelete

    fun isStreamStart(message: AppMessageRecordFfi): Boolean = message.kind == KindAgentStreamStart

    fun isStreamFinal(message: AppMessageRecordFfi): Boolean {
        return message.kind == KindChat &&
            tagValue(message, StreamTag) != null &&
            (tagValue(message, StreamStartTag) != null || tagValue(message, StreamHashTag) != null)
    }

    fun streamId(message: AppMessageRecordFfi): String? = tagValue(message, StreamTag)

    fun replyTargetMessageId(message: AppMessageRecordFfi): String? = tagValue(message, QuoteRefTag)

    fun deletedTargetMessageIds(message: AppMessageRecordFfi): List<String> {
        if (!isDelete(message)) return emptyList()
        return tagValues(message, EventRefTag)
    }

    fun eventTag(targetMessageId: String): MessageTagFfi = MessageTagFfi(listOf(EventRefTag, targetMessageId))

    fun quoteTag(targetMessageId: String): MessageTagFfi = MessageTagFfi(listOf(QuoteRefTag, targetMessageId))

    fun streamTag(streamId: String): MessageTagFfi = MessageTagFfi(listOf(StreamTag, streamId))

    private fun isMedia(message: AppMessageRecordFfi): Boolean {
        return message.kind == KindChat && message.tags.any { it.values.firstOrNull() == ImetaTag }
    }

    private fun firstEventRef(message: AppMessageRecordFfi): String? = tagValue(message, EventRefTag)

    private fun tagValue(message: AppMessageRecordFfi, name: String): String? {
        return message.tags
            .firstOrNull { it.values.firstOrNull() == name }
            ?.values
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    private fun tagValues(message: AppMessageRecordFfi, name: String): List<String> {
        return message.tags
            .filter { it.values.firstOrNull() == name }
            .mapNotNull { it.values.getOrNull(1)?.takeIf { value -> value.isNotBlank() } }
    }

    private fun imetaField(message: AppMessageRecordFfi, fieldName: String): String? {
        val prefix = "$fieldName "
        return message.tags
            .asSequence()
            .filter { it.values.firstOrNull() == ImetaTag }
            .flatMap { it.values.drop(1).asSequence() }
            .firstNotNullOfOrNull { value ->
                value.removePrefix(prefix)
                    .takeIf { value.startsWith(prefix) && it.isNotBlank() }
            }
    }

    private data class ReactionRecord(
        val targetMessageId: String,
        val emoji: String,
        val sender: String,
    )
}
