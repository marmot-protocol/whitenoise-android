package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemProjector
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.core.agent.AgentNoticeKind

/** Shows the turn and open requests. Routine activity lives under its turn's disclosure. */
internal fun conversationAttentionTimeline(timeline: List<TimelineMessage>): List<TimelineMessage> {
    val rows = conversationRenderedTimeline(timeline)
    val agentRows =
        rows.mapNotNull { row ->
            if (row.projected?.deleted == true || row.record.invalidated) return@mapNotNull null
            AgentItemProjector.project(row.record)?.let { AgentTranscriptItem(row, it) }
        }
    if (agentRows.isEmpty()) return rows
    val turns = agentRows.filter { it.item.body is AgentItemBody.Turn }.associateBy { it.key }
    val details = agentRows.filterNot { it.item.requiresTranscriptRow }.groupBy { it.key }
    val firstTurnBySession = turns.values.groupBy { it.key.first to it.key.second }.mapValues { it.value.first() }
    val attachments = LinkedHashMap<String, MutableList<AgentTranscriptItem>>()
    for ((key, items) in details) {
        val matchingTurn = turns[key] ?: firstTurnBySession[key.first to key.second].takeIf { key.third == null }
        val anchor = matchingTurn?.row ?: items.firstOrNull { it.item.body !is AgentItemBody.Session }?.row
        if (anchor != null) attachments.getOrPut(anchor.id, ::mutableListOf).addAll(items)
    }
    val waitingTurns = agentRows.filter { it.item.isWaitingForInput }.mapNotNullTo(HashSet()) { turns[it.key]?.row?.id }
    val hidden = details.values.flatten().mapTo(HashSet()) { it.row.id } - attachments.keys
    return rows.mapNotNull { row ->
        val activity = attachments[row.id]
        when {
            row.id in hidden -> null
            activity != null || row.id in waitingTurns ->
                row.copy(
                    agentActivity = activity.orEmpty().map { it.item },
                    agentWaitingForInput = row.id in waitingTurns,
                )
            else -> row
        }
    }
}

private data class AgentTranscriptItem(
    val row: TimelineMessage,
    val item: AgentItem,
) {
    val key get() = Triple(row.record.sender.lowercase(), item.session, item.turn)
}

private val AgentItem.requiresTranscriptRow: Boolean
    get() =
        when (val content = body) {
            is AgentItemBody.Turn, is AgentItemBody.Error -> true
            is AgentItemBody.Notice ->
                content.kind == AgentNoticeKind.Refused || content.kind == AgentNoticeKind.Warning
            is AgentItemBody.Approval, is AgentItemBody.Question -> state == AgentItemState.Waiting
            else -> false
        }

private val AgentItem.isWaitingForInput: Boolean
    get() = state == AgentItemState.Waiting && (body is AgentItemBody.Approval || body is AgentItemBody.Question)
