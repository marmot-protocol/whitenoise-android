package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Immutable
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi

enum class MessageStatus {
    Received,
    Pending,
    Sent,
    Failed,
    Streaming,
}

enum class OutgoingMessageIndicator {
    Sending,
    Sent,
    Failed,
}

/**
 * Chat-list delivery tick for the row's projected last message. The engine
 * scopes the state itself: NOT_APPLICABLE covers incoming messages, so no
 * sender comparison is needed here.
 */
fun ChatListMessageDeliveryStateFfi.outgoingIndicator(): OutgoingMessageIndicator? =
    when (this) {
        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE -> null
        ChatListMessageDeliveryStateFfi.PENDING -> OutgoingMessageIndicator.Sending
        ChatListMessageDeliveryStateFfi.DELIVERED -> OutgoingMessageIndicator.Sent
        ChatListMessageDeliveryStateFfi.FAILED -> OutgoingMessageIndicator.Failed
    }

fun MessageStatus.outgoingIndicator(): OutgoingMessageIndicator? =
    when (this) {
        MessageStatus.Pending -> OutgoingMessageIndicator.Sending
        MessageStatus.Received,
        MessageStatus.Sent,
        -> OutgoingMessageIndicator.Sent
        MessageStatus.Failed -> OutgoingMessageIndicator.Failed
        MessageStatus.Streaming -> null
    }

// Immutable so MessageBubble's `item` param is stable and bubbles can skip
// recomposition; the UniFFI record types are all-val data classes but carry
// no Compose stability information on their own. See #110.
@Immutable
data class TimelineMessage(
    val id: String,
    val record: AppMessageRecordFfi,
    val status: MessageStatus,
    val projected: TimelineMessageRecordFfi? = null,
    val timelineOrder: ULong = 0uL,
    /** Position supplied by MDK's authoritative bounded timeline window. */
    val authoritativeOrder: ULong? = null,
    /**
     * Immediate durable stream parent whose rendered row this row must follow.
     * Null for ordinary authoritative rows and unanchored local overlays.
     */
    val displayAfterMessageIdHex: String? = null,
    /**
     * Immutable retention snapshot captured when this local send was accepted.
     * It keeps the waiting timer accurate and supplies the bounded local expiry
     * fallback only until MarmotKit projects the authoritative source-epoch
     * duration and deadline.
     */
    val retentionAtSendSeconds: ULong? = null,
    /** Transient row identity retained across an exact local-send handoff; actions still use [record]. */
    val presentationId: String = id,
)

/**
 * Local optimistic state for an in-flight edit of one's own message: the new
 * body to display immediately and whether the kind-1009 publish is still
 * [MessageStatus.Pending] or has [MessageStatus.Failed]. [preEditText] is the
 * body shown before the edit (the latest applied version, or the original
 * plaintext) so a failure can revert the bubble verbatim.
 */
@Immutable
data class OptimisticEdit(
    val text: String,
    val preEditText: String,
    val status: MessageStatus,
    val durableIntent: DurablePendingEditIntent? = null,
    val nativeEditMessageId: String? = null,
    val nativeRevisionRejected: Boolean = false,
)

data class ReactionParticipant(
    val sender: String,
    val emoji: String,
    val reactedAt: ULong,
)
