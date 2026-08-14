package dev.ipf.whitenoise.android.state

/**
 * Conversation-scoped intent for the two-stage jump button. This is transient
 * UI state: only a stable message id is retained, never message content.
 */
internal data class ConversationUnreadJumpState(
    val pendingMessageId: String? = null,
    val unreadStackActive: Boolean = false,
    val initialized: Boolean = false,
) {
    /** Retire this stack without allowing a later arrival to retarget it. */
    fun suppressCurrentStack(): ConversationUnreadJumpState =
        copy(
            pendingMessageId = null,
            unreadStackActive = true,
            initialized = true,
        )
}

/**
 * Freezes the first received, non-derived row when an off-tail unread stack
 * begins. Once consumed or invalidated, that stack cannot capture a new target
 * until its unread count returns to zero.
 */
@Suppress("ReturnCount") // Guard clauses make each state-machine terminal condition explicit.
internal fun reconcileConversationUnreadJump(
    current: ConversationUnreadJumpState,
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
    unreadCount: Int,
    nearBottom: Boolean,
): ConversationUnreadJumpState {
    if (unreadCount <= 0) {
        return ConversationUnreadJumpState(initialized = true)
    }
    if (!current.initialized) {
        // Entry unread is owned by the initial-anchor flow. Seed it as an
        // already-active stack so opening a chat does not create a new target.
        return ConversationUnreadJumpState(
            unreadStackActive = true,
            initialized = true,
        )
    }
    if (nearBottom) return current.suppressCurrentStack()

    val pending = current.pendingMessageId
    if (pending != null) {
        val stillResolvable =
            timeline.any { message ->
                message.record.messageIdHex == pending && message.isUnreadJumpDestination()
            }
        return if (stillResolvable) current else current.suppressCurrentStack()
    }
    if (current.unreadStackActive) return current

    val anchorIndex =
        when {
            readAnchorMessageId == null -> -1
            else -> timeline.indexOfFirst { it.record.messageIdHex == readAnchorMessageId }
        }
    val candidate =
        if (readAnchorMessageId != null && anchorIndex < 0) {
            null
        } else {
            timeline
                .asSequence()
                .drop(anchorIndex + 1)
                .firstOrNull(TimelineMessage::isUnreadJumpDestination)
                ?.record
                ?.messageIdHex
                ?.takeIf(String::isNotBlank)
        }
    return ConversationUnreadJumpState(
        pendingMessageId = candidate,
        unreadStackActive = true,
        initialized = true,
    )
}

private fun TimelineMessage.isUnreadJumpDestination(): Boolean =
    record.direction == "received" &&
        !isDerivedStateKind(record.kind) &&
        record.messageIdHex.isNotBlank()
