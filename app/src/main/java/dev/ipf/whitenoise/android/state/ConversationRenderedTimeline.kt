package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.agent.AgentItemProjector

/**
 * The rows the transcript draws, in chronological order.
 *
 * Edits mutate their original message and never take a slot. An agent item takes one slot at its
 * first event and shows its highest version there, so a tool call that finishes replaces its
 * running state in place instead of adding a row. A later version whose first event is not loaded
 * stays hidden until it is. Answers and controls sent to an agent have no row; the item they settle
 * shows their effect.
 */
internal fun conversationRenderedTimeline(timeline: List<TimelineMessage>): List<TimelineMessage> {
    if (timeline.none { AgentItemProjector.isAgentItem(it.record) || AgentItemProjector.isAgentRequest(it.record) }) {
        return timeline.filterNot { MessageProjector.isEdit(it.record) }
    }
    val latest = latestAgentItemVersions(timeline)
    return timeline.mapNotNull { message ->
        val record = message.record
        when {
            takesNoTranscriptRow(record) -> null
            !AgentItemProjector.isAgentItem(record) -> message
            else -> {
                val newest = latest[record.messageIdHex]
                if (newest == null || newest === record) {
                    message
                } else {
                    message.copy(record = record.copy(plaintext = newest.plaintext, tags = newest.tags))
                }
            }
        }
    }
}

/**
 * Records that never occupy a transcript slot: edits, answers and controls sent to an agent, and
 * later versions of an agent item. The decision depends on the record alone, so counts and indexes
 * taken with it match [conversationRenderedTimeline].
 */
internal fun takesNoTranscriptRow(record: AppMessageRecordFfi): Boolean =
    MessageProjector.isEdit(record) ||
        AgentItemProjector.isAgentRequest(record) ||
        (AgentItemProjector.isAgentItem(record) && AgentItemProjector.firstEventId(record) != record.messageIdHex)

/** The highest version of each agent item, keyed by the item's first event. */
private fun latestAgentItemVersions(timeline: List<TimelineMessage>): Map<String, AppMessageRecordFfi> {
    val latest = HashMap<String, Pair<Long, AppMessageRecordFfi>>()
    for (message in timeline) {
        val record = message.record
        val version = AgentItemProjector.version(record)?.takeIf { AgentItemProjector.isAgentItem(record) } ?: continue
        val firstId = AgentItemProjector.firstEventId(record)
        val current = latest[firstId]
        // Equal versions keep the later delivery; the agent never reuses a version for new content.
        if (current == null || version >= current.first) latest[firstId] = version to record
    }
    return latest.mapValues { it.value.second }
}
