package dev.ipf.darkmatter.core

import org.marmotprotocol.marmotkit.AppMessagePayloadFfi
import org.marmotprotocol.marmotkit.AppMessageRecordFfi

data class ReactionTally(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

object MessageProjector {
    fun displayBody(message: AppMessageRecordFfi): String {
        return when (val payload = message.appMessage) {
            is AppMessagePayloadFfi.Reply -> payload.text
            is AppMessagePayloadFfi.Media -> payload.caption?.takeIf { it.isNotBlank() }
                ?: payload.fileName
            else -> message.plaintext
        }
    }

    fun previewText(message: AppMessageRecordFfi?): String {
        if (message == null) return "No messages yet"
        return when (val payload = message.appMessage) {
            is AppMessagePayloadFfi.Reaction -> "Reacted ${payload.emoji.ifBlank { "to a message" }}"
            is AppMessagePayloadFfi.Delete -> "Deleted a message"
            is AppMessagePayloadFfi.Retry -> "Retried a message"
            is AppMessagePayloadFfi.Media -> payload.caption?.takeIf { it.isNotBlank() }
                ?: payload.fileName
            is AppMessagePayloadFfi.Reply -> payload.text
            null -> message.plaintext.ifBlank { "Message" }
        }
    }

    fun reactionTallies(
        records: List<AppMessageRecordFfi>,
        targetMessageId: String,
        myAccountId: String?,
    ): List<ReactionTally> {
        val sendersByEmoji = linkedMapOf<String, MutableSet<String>>()
        records
            .asSequence()
            .filter { it.appMessage is AppMessagePayloadFfi.Reaction }
            .sortedBy { it.recordedAt }
            .forEach { record ->
                val reaction = record.appMessage as AppMessagePayloadFfi.Reaction
                if (reaction.targetMessageId != targetMessageId) return@forEach
                if (reaction.removed) {
                    for (senders in sendersByEmoji.values) {
                        senders.remove(record.sender)
                    }
                } else if (reaction.emoji.isNotBlank()) {
                    sendersByEmoji.getOrPut(reaction.emoji) { linkedSetOf() }.add(record.sender)
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
}
