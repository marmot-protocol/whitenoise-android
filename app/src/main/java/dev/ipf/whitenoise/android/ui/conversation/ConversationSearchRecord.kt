package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DisappearingMessageSweep
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.mediaReferencesFor

/** Loaded rows use displayed edits, accepted attachments and the same predicate as stored history. */
internal fun conversationSearchRecord(
    controller: ConversationController,
    item: TimelineMessage,
    nowMillis: Long,
): ChatListMessageSearch.SearchableRecord {
    val record = item.record
    val timestamp = item.projected?.timelineAt ?: record.recordedAt
    val expired =
        DisappearingMessageSweep.isLocallyExpired(
            nowMillis,
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = timestamp,
                expiresAtLocalSeconds = record.retentionExpiresAt?.takeIf { it > 0uL },
                retentionAtSendSeconds = record.retentionSeconds?.takeIf { it > 0uL },
            ),
        )
    val media = controller.mediaReferencesFor(item)
    return object : ChatListMessageSearch.SearchableRecord {
        override val kind = record.kind
        override val deleted =
            expired ||
                item.projected?.deleted == true ||
                MessageProjector.isDeleted(record.messageIdHex, controller.deletedMessageIds)
        override val plaintext = controller.displayedText(record)
        override val messageIdHex = record.messageIdHex
        override val timelineAt = timestamp
        override val sender = record.sender
        override val mediaTypes = media.map { it.mediaType }
        override val mediaLabels = media.map { it.fileName.ifBlank { it.mediaType } }
    }
}
