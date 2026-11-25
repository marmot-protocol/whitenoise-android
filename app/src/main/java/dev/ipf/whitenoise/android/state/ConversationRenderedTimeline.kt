package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.agent.AgentItemProjector

/**
 * The rows the transcript draws, in chronological order.
 *
 * Edits mutate their original message and never take a slot. An agent item takes one slot at its
 * first event and shows its highest version there, so a tool call that finishes replaces its
 * running state in place instead of adding a row. Only the first event's sender can update it,
 * under the same item id. A later version whose first event is not loaded stays hidden until it is.
 * Answers and controls sent to an agent have no row; the item they settle shows their effect.
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
        (AgentItemProjector.isAgentItem(record) && !isReadableFirstOrUnversioned(record))

/**
 * A readable item holds a row only at its first version. An unversioned standalone record keeps
 * its generic row; one that names another first event is an orphan and stays hidden.
 */
private fun isReadableFirstOrUnversioned(record: AppMessageRecordFfi): Boolean =
    AgentItemProjector.isFirstVersion(record) ||
        (AgentItemProjector.version(record) == null && AgentItemProjector.firstEventId(record) == record.messageIdHex)

/**
 * The highest version of each agent item, keyed by the item's first event. A version counts only
 * when it comes from the first event's sender and names the same item, so another member of the
 * chat cannot rewrite an agent's row.
 */
private fun latestAgentItemVersions(timeline: List<TimelineMessage>): Map<String, AppMessageRecordFfi> {
    val firsts =
        timeline
            .map { it.record }
            .filter { AgentItemProjector.isAgentItem(it) && AgentItemProjector.isFirstVersion(it) }
            .associateBy { it.messageIdHex }
    val latest = HashMap<String, Pair<Long, AppMessageRecordFfi>>()
    for (message in timeline) {
        val record = message.record
        // A version the row cannot draw never replaces one it can.
        val version = AgentItemProjector.version(record)?.takeIf { AgentItemProjector.isReadable(record) }
        // Only the first event itself or a later version that names it can fill the row.
        val first =
            firsts[AgentItemProjector.firstEventId(record)]?.takeIf {
                it.isSameItemAs(record) && (it === record || (version ?: 0L) > 1L)
            }
        if (version == null || first == null) continue
        val current = latest[first.messageIdHex]
        // Equal versions keep the later delivery; the agent never reuses a version for new content.
        if (current == null || version >= current.first) latest[first.messageIdHex] = version to record
    }
    return latest.mapValues { it.value.second }
}

private fun AppMessageRecordFfi.isSameItemAs(other: AppMessageRecordFfi): Boolean =
    sender.equals(other.sender, ignoreCase = true) &&
        AgentItemProjector.itemId(this) == AgentItemProjector.itemId(other)
