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
 * Which rows the open chat has already rendered, so a row that a display-position reorder moves past the followed
 * anchor is never announced as an arrival. Rows the follow pass saw and rows an older page added above the anchor
 * both count.
 */
internal class ConversationArrivalBaseline {
    private val renderedIds = HashSet<String>()

    /** After any timeline change, rows up to the followed [anchorId] are known, including an older page above it. */
    fun noteRendered(
        timeline: List<TimelineMessage>,
        anchorId: String?,
    ) {
        val end = timeline.indexOfFirst { it.presentationId == anchorId }
        for (index in 0..end) renderedIds += timeline[index].presentationId
    }

    /** A follow pass that cannot be an append: every row it shows is known. */
    fun markAllRendered(timeline: List<TimelineMessage>) {
        timeline.mapTo(renderedIds) { it.presentationId }
    }

    /**
     * A follow pass: the arrivals after [previousLatestId], or none when the pass is not an append. Every row it
     * shows is known afterwards.
     */
    fun follow(
        timeline: List<TimelineMessage>,
        previousLatestId: String?,
        isMine: (TimelineMessage) -> Boolean,
    ): List<TimelineMessage> {
        val arrivals =
            previousLatestId
                ?.let { conversationIncomingArrivals(timeline, it, renderedIds, isMine) }
                .orEmpty()
        markAllRendered(timeline)
        return arrivals
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
