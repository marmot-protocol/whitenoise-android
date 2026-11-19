package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.core.AgentOperationProjector
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.timelineRowKind
import dev.ipf.whitenoise.android.state.TimelineMessage

/** Whether adjacent timeline items participate in one visible message-bubble sender run. */
internal fun conversationBubbleRowsShareSenderRun(
    first: TimelineMessage,
    second: TimelineMessage,
    streamingDebugEnabled: Boolean,
    deletedMessageIds: Set<String>,
): Boolean =
    timelineItemRendersAsConversationBubble(first, streamingDebugEnabled, deletedMessageIds) &&
        timelineItemRendersAsConversationBubble(second, streamingDebugEnabled, deletedMessageIds) &&
        GroupProjector.messagesShareTranscriptSenderRun(
            firstSender = first.record.sender,
            firstRecordedAt = first.record.recordedAt,
            secondSender = second.record.sender,
            secondRecordedAt = second.record.recordedAt,
            sameDay = !differentDay(first.record.recordedAt, second.record.recordedAt),
        )

/** Classifies rows that participate in adjacent sender grouping as message bubbles. */
private fun timelineItemRendersAsConversationBubble(
    item: TimelineMessage,
    streamingDebugEnabled: Boolean,
    deletedMessageIds: Set<String>,
): Boolean =
    when (timelineRowKind(item.record, streamingDebugEnabled)) {
        TimelineRowKind.Bubble -> true
        TimelineRowKind.AgentOperation ->
            !shouldRenderDedicatedAgentOperationRow(
                projectedDeleted = item.projected?.deleted == true,
                optimisticallyDeleted = MessageProjector.isDeleted(item.record.messageIdHex, deletedMessageIds),
                invalidated = item.projected?.invalidationStatus != null,
            ) ||
                AgentOperationProjector.project(item.record) == null
        TimelineRowKind.GroupSystem,
        TimelineRowKind.DebugRow,
        -> false
    }
