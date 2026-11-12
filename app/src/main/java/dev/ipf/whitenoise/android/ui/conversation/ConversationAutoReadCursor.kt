package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.state.TimelineMessage

/**
 * Tail position captured when a foreground-only auto-read session is stopped
 * by backgrounding. It is independent of the read watermark because an open
 * conversation may mark messages read before speech resumes.
 */
internal data class ConversationAutoReadCursor(
    val timelineItemId: String?,
    val timelineOrder: ULong,
)

internal fun conversationAutoReadCursor(timeline: List<TimelineMessage>): ConversationAutoReadCursor {
    val tail = timeline.lastOrNull()
    return ConversationAutoReadCursor(
        timelineItemId = tail?.id,
        timelineOrder = tail?.timelineOrder ?: 0uL,
    )
}

/**
 * Rows that arrived after [cursor]. Exact item identity is preferred while the
 * anchor remains loaded; timeline order keeps the cursor usable if the bounded
 * window trims that anchor before foreground synchronization completes.
 */
internal fun conversationMessagesAfterAutoReadCursor(
    timeline: List<TimelineMessage>,
    cursor: ConversationAutoReadCursor,
): List<TimelineMessage> {
    val anchorIndex = cursor.timelineItemId?.let { id -> timeline.indexOfFirst { it.id == id } } ?: -1
    return when {
        anchorIndex >= 0 -> timeline.drop(anchorIndex + 1)
        cursor.timelineItemId == null -> timeline
        cursor.timelineOrder == 0uL -> emptyList()
        else -> timeline.filter { it.timelineOrder > cursor.timelineOrder }
    }
}
