package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.agent.AgentItemReader
import org.json.JSONObject

/** The first-event ID addresses a request to this row, including a session's controls. */
internal data class AgentRequestTarget(
    val rowId: String,
    val agent: String,
    val isSession: Boolean = false,
)

internal data class AgentRequestAdmission(
    val eventId: String,
)

internal class AgentNativeRequestHistory(
    records: List<AppMessageRecordFfi>,
) {
    private val requests =
        records.filter { it.kind == AgentItemReader.REQUEST_KIND && it.direction == "sent" }
    val invalidatedIds: Set<String> = requests.filter { it.invalidated }.map { it.messageIdHex }.toSet()
    val confirmations: Set<Pair<String, String>> =
        records
            .asSequence()
            .filter { !it.invalidated && it.kind in AGENT_SETTLEMENT_KINDS }
            .flatMap { row -> row.tags.agentSettlementIds().map { row.sender to it } }
            .toSet()

    fun outstanding(target: AgentRequestTarget): AgentRequestAdmission? {
        val matched = requests.filter { it.matchesTarget(target) }
        val newest = matched.maxWithOrNull(compareBy({ it.recordedAt }, { it.receivedAt })) ?: return null
        // Retention can prune the settlement for a previous request independently of the request.
        return matched
            .asSequence()
            .filter { request ->
                request.recordedAt == newest.recordedAt && request.receivedAt == newest.receivedAt
            }.filterNot { it.invalidated || (target.agent to it.messageIdHex) in confirmations }
            .maxByOrNull { it.messageIdHex }
            ?.let { AgentRequestAdmission(it.messageIdHex) }
    }
}

/** The native send transaction records the raw request before returning durable acceptance. */
internal fun MarmotInterface.agentNativeRequestHistory(
    account: String,
    group: String,
    targets: List<AgentRequestTarget>,
    admittedIds: Set<String> = emptySet(),
): AgentNativeRequestHistory {
    val requests = ownAgentRequests(account, group)
    val targetsById = targets.associateBy(AgentRequestTarget::rowId)
    val relevant =
        requests.filter { request ->
            val target = targetsById[request.singleTag("e")]
            request.messageIdHex in admittedIds || (target != null && request.matchesTarget(target))
        }
    val awaiting =
        relevant
            .filterNot { it.invalidated }
            .mapNotNull { request -> request.singleTag("p")?.let { it to request.messageIdHex } }
            .toSet()
    val settlements =
        readAgentSettlements(awaiting) { limit ->
            messages(account, group, limit, AGENT_SETTLEMENT_KINDS)
        }
    return AgentNativeRequestHistory(relevant + settlements)
}

internal fun MarmotInterface.ownAgentRequests(
    account: String,
    group: String,
): List<AppMessageRecordFfi> {
    val kinds = listOf(AgentItemReader.REQUEST_KIND)
    return messages(account, group, null, kinds).filter { it.direction == "sent" }
}

/** Agent-created timestamps can predate the request by any amount. Stop on a match or exhaustion. */
internal fun readAgentSettlements(
    awaiting: Set<Pair<String, String>>,
    read: (UInt) -> List<AppMessageRecordFfi>,
): List<AppMessageRecordFfi> {
    if (awaiting.isEmpty()) return emptyList()
    var limit = AGENT_SETTLEMENT_REPLAY_PAGE
    while (true) {
        val replay = read(limit)
        val unmatched = awaiting.toMutableSet()
        val matched = mutableListOf<AppMessageRecordFfi>()
        for (row in replay.asSequence().filterNot { it.invalidated }) {
            val count = unmatched.size
            row.tags.agentSettlementIds().forEach { unmatched.remove(row.sender to it) }
            if (unmatched.size < count) matched.add(row)
            if (unmatched.isEmpty()) break
        }
        if (unmatched.isEmpty() || replay.size.toUInt() < limit) return matched
        limit *= 2u
    }
}

private fun AppMessageRecordFfi.matchesTarget(target: AgentRequestTarget): Boolean {
    val details =
        singleTag("details")?.takeIf { singleTag("p") == target.agent && singleTag("e") == target.rowId }
            ?: return false
    val action = runCatching { JSONObject(details).optString("action") }.getOrNull()
    return action in if (target.isSession) SESSION_ACTIONS else ITEM_ACTIONS
}

private fun AppMessageRecordFfi.singleTag(name: String): String? {
    val tag = tags.singleOrNull { it.values.firstOrNull() == name }
    return tag?.values?.getOrNull(1)
}

internal fun List<MessageTagFfi>.agentSettlementIds(): Sequence<String> =
    asSequence().mapNotNull { tag ->
        tag.values.getOrNull(1)?.takeIf { tag.values.firstOrNull() == "settles" }
    }

private val ITEM_ACTIONS = setOf("choice", "questions", "interrupt")
private val SESSION_ACTIONS = setOf("set", "new_session", "own")
internal val AGENT_SETTLEMENT_KINDS = listOf(9uL, 1009uL, 1111uL, AgentItemReader.ITEM_KIND)
private const val AGENT_SETTLEMENT_REPLAY_PAGE = 400u
