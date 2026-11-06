package dev.ipf.whitenoise.android.ui.conversation

import android.content.res.Resources
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TimelineMessage

/**
 * Chat messages someone else sent among the rows a live append added after [previousLatestId], oldest first. An
 * unknown [previousLatestId] means the timeline was replaced rather than appended to, so nothing counts as arrived.
 * Rows in [previousRowIds] were already rendered, so one that a reorder moved past the anchor is not new.
 */
internal fun conversationIncomingArrivals(
    renderedTimeline: List<TimelineMessage>,
    previousLatestId: String,
    previousRowIds: Set<String>,
    isMine: (TimelineMessage) -> Boolean,
): List<TimelineMessage> {
    val previousIndex = renderedTimeline.indexOfFirst { it.presentationId == previousLatestId }
    if (previousIndex < 0) return emptyList()
    return renderedTimeline
        .drop(previousIndex + 1)
        .filter { row ->
            row.record.kind == CHAT_MESSAGE_KIND && row.presentationId !in previousRowIds && !isMine(row)
        }
}

/**
 * What the screen reader hears when messages arrive in the open chat, or null when nothing arrived. One message names
 * its sender; a burst is counted, so a busy group does not queue one announcement per message.
 */
internal fun conversationArrivalAnnouncement(
    arrivals: List<TimelineMessage>,
    senderName: (TimelineMessage) -> String,
    resources: Resources,
): String? =
    when (arrivals.size) {
        0 -> null
        1 -> resources.getString(R.string.conversation_new_message_from, senderName(arrivals.single()))
        else -> resources.getQuantityString(R.plurals.conversation_new_messages, arrivals.size, arrivals.size)
    }

private const val CHAT_MESSAGE_KIND = 9uL
