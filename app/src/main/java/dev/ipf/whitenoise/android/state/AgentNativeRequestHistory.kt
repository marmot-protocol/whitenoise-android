package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.agent.AgentItemReader
import org.json.JSONObject

/** Identity of a UI action row. A session's controls share one slot even though they carry no e tag. */
internal data class AgentRequestTarget(
    val rowId: String,
    val agent: String,
    val sessionStartedAt: ULong? = null,
)

internal data class AgentRequestAdmission(
    val eventId: String,
    val expiresAtMillis: Long? = null,
)

internal class AgentNativeRequestHistory(
    records: List<AppMessageRecordFfi>,
) {
    private val requests =
        records.filter { it.kind == AgentItemReader.REQUEST_KIND && it.direction == "sent" }
    private val settlements =
        records
            .asSequence()
            .filter { !it.invalidated && it.kind in AGENT_SETTLEMENT_KINDS }
            .flatMap { row -> row.tags.agentSettlementIds().map { row.sender to it } }
            .toSet()

    fun outstanding(target: AgentRequestTarget): AgentRequestAdmission? =
        requests
            .latestFor(target)
            ?.takeUnless { concluded(target.agent, it.messageIdHex) }
            ?.let { request ->
                val sentAtMillis = request.recordedAt.toLong() * 1000
                val expiry = target.sessionStartedAt?.let { untargetedAgentRequestDeadline(sentAtMillis) }
                AgentRequestAdmission(request.messageIdHex, expiry)
            }

    fun concluded(
        agent: String,
        eventId: String,
    ): Boolean = (agent to eventId) in settlements || requests.any { it.messageIdHex == eventId && it.invalidated }
}

/** Real agents discard untargeted controls more than five minutes old. Allow one extra second. */
internal fun untargetedAgentRequestDeadline(sentAtMillis: Long): Long = sentAtMillis + UNTARGETED_CONTROL_TIMEOUT_MILLIS

/** The native send transaction records the raw request before returning durable acceptance. */
internal fun MarmotInterface.agentNativeRequestHistory(
    account: String,
    group: String,
    targets: List<AgentRequestTarget>,
    admittedIds: Set<String> = emptySet(),
): AgentNativeRequestHistory {
    val requests = ownAgentRequests(account, group)
    val relevant = targets.mapNotNull(requests::latestFor) + requests.filter { it.messageIdHex in admittedIds }
    val oldest = relevant.filterNot { it.invalidated }.minOfOrNull { it.recordedAt }
    val settlements = oldest?.let { readAgentSettlements(account, group, it) }.orEmpty()
    return AgentNativeRequestHistory(relevant + settlements)
}

internal fun MarmotInterface.ownAgentRequests(
    account: String,
    group: String,
): List<AppMessageRecordFfi> = messages(account, group, null, listOf(AgentItemReader.REQUEST_KIND)).filter { it.direction == "sent" }

private fun List<AppMessageRecordFfi>.latestFor(target: AgentRequestTarget): AppMessageRecordFfi? =
    asSequence()
        .filter { it.matchesTarget(target) }
        .maxWithOrNull(compareBy({ it.recordedAt }, { it.receivedAt }, { it.messageIdHex }))

private fun MarmotInterface.readAgentSettlements(
    account: String,
    group: String,
    oldestRequest: ULong,
): List<AppMessageRecordFfi> {
    val start = oldestRequest - minOf(oldestRequest, SETTLEMENT_CLOCK_SKEW_SECONDS)
    var limit = AGENT_SETTLEMENT_REPLAY_PAGE
    while (true) {
        val replay = messages(account, group, limit, AGENT_SETTLEMENT_KINDS)
        if (replay.size.toUInt() < limit || replay.first().recordedAt <= start) return replay
        limit *= 2u
    }
}

private fun AppMessageRecordFfi.matchesTarget(target: AgentRequestTarget): Boolean {
    val details = singleTag("details")?.takeIf { singleTag("p") == target.agent } ?: return false
    val action = runCatching { JSONObject(details).optString("action") }.getOrNull()
    val startedAt = target.sessionStartedAt
    return if (startedAt == null) {
        singleTag("e") == target.rowId && action in TARGETED_ACTIONS
    } else {
        recordedAt >= startedAt && tags.none { it.values.firstOrNull() == "e" } && action in SESSION_ACTIONS
    }
}

private fun AppMessageRecordFfi.singleTag(name: String): String? = tags.singleOrNull { it.values.firstOrNull() == name }?.values?.getOrNull(1)

internal fun List<MessageTagFfi>.agentSettlementIds(): Sequence<String> =
    asSequence().mapNotNull { tag ->
        tag.values.getOrNull(1)?.takeIf { tag.values.firstOrNull() == "settles" }
    }

private val TARGETED_ACTIONS = setOf("choice", "questions", "interrupt")
private val SESSION_ACTIONS = setOf("set", "new_session", "own")
internal val AGENT_SETTLEMENT_KINDS = listOf(9uL, 1009uL, 1111uL, AgentItemReader.ITEM_KIND)
private const val AGENT_SETTLEMENT_REPLAY_PAGE = 400u
private const val SETTLEMENT_CLOCK_SKEW_SECONDS = 300uL
private const val UNTARGETED_CONTROL_TIMEOUT_MILLIS = 301_000L
