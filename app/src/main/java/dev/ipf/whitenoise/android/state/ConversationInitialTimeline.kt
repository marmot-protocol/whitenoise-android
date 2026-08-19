package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi

/**
 * Builds the short-lived timeline shown while the authoritative MDK page opens.
 *
 * The chat-list projection deliberately carries less data than a timeline row.
 * Only an undeleted, complete plain-text kind-9 preview can therefore be
 * reconstructed without inventing tags or rendering a derived protocol event as
 * a chat bubble. Full optimistic rows are already conversation-scoped UI state
 * and take precedence when the chat-list preview points at the same message.
 */
internal fun initialConversationTimeline(
    preview: ChatListMessagePreviewFfi?,
    groupIdHex: String,
    pendingConfirmation: Boolean,
    optimisticMessages: Collection<TimelineMessage>,
): List<TimelineMessage> {
    if (pendingConfirmation) return emptyList()
    val byMessageId = linkedMapOf<String, TimelineMessage>()
    optimisticMessages.forEach { message ->
        val messageId = message.record.messageIdHex.lowercase()
        if (messageId.isNotBlank()) byMessageId[messageId] = message
    }
    preview
        ?.toInitialTimelineMessage(groupIdHex)
        ?.let { seed -> byMessageId.putIfAbsent(seed.record.messageIdHex.lowercase(), seed) }
    return byMessageId.values.sortedWith(::compareTimelineMessages)
}

internal fun shouldDiscardInitialTimelineSeedForFailure(hasPublishedAuthoritativeTimeline: Boolean): Boolean = !hasPublishedAuthoritativeTimeline

private fun ChatListMessagePreviewFfi.toInitialTimelineMessage(groupIdHex: String): TimelineMessage? {
    if (!ConversationController.HEX_MESSAGE_ID.matches(messageIdHex)) return null
    if (!ConversationController.HEX_MESSAGE_ID.matches(groupIdHex)) return null
    if (!ConversationController.HEX_MESSAGE_ID.matches(sender)) return null
    if (deleted || kind != CHAT_MESSAGE_KIND || plaintext.isBlank()) return null
    if (attachmentKind != null || attachmentCount != 0u) return null
    if (contentTokens.truncated) return null

    val status =
        when (deliveryState) {
            ChatListMessageDeliveryStateFfi.NOT_APPLICABLE -> MessageStatus.Received
            ChatListMessageDeliveryStateFfi.DELIVERED -> MessageStatus.Sent
            // Pending has a truthful display-only state and needs no retry
            // metadata. Keeping it eligible matters for offline conversations,
            // where it is often the only safe first-frame preview.
            ChatListMessageDeliveryStateFfi.PENDING -> MessageStatus.Pending
            // Failed rows expose a retry action which requires the complete
            // optimistic/projected record, not this display-shaped preview.
            ChatListMessageDeliveryStateFfi.FAILED -> return null
        }
    val record =
        AppMessageRecordFfi(
            messageIdHex = messageIdHex,
            direction = if (deliveryState == ChatListMessageDeliveryStateFfi.NOT_APPLICABLE) "received" else "sent",
            groupIdHex = groupIdHex,
            sender = sender,
            plaintext = plaintext,
            contentTokens = contentTokens,
            kind = kind,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = timelineAt,
            receivedAt = timelineAt,
        )
    return TimelineMessage(
        id = "msg:$messageIdHex",
        record = record,
        status = status,
    )
}

private const val CHAT_MESSAGE_KIND = 9uL
