package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.THREAD_MESSAGE_KIND

/**
 * Local progress of the viewer's answer or control. Admission and agent confirmation are separate.
 */
internal enum class AgentRequestStatus {
    Idle,
    Sending,
    AwaitingAgent,
    Unconfirmed,
}

/** Tracks requests across the inline rows, background activity and native row updates. */
@Stable
internal class AgentRequestProgress {
    private data class Pending(
        val agent: String,
        val admission: AgentRequestAdmission? = null,
    )

    private val pending = mutableStateMapOf<String, Pending>()
    private val unconfirmed = mutableStateMapOf<String, String>()

    val hasAdmittedRequests: Boolean get() = pending.values.any { it.admission != null }
    val admittedEventIds: Set<String> get() = pending.values.mapNotNull { it.admission?.eventId }.toSet()
    val nextExpiryMillis: Long? get() = pending.values.mapNotNull { it.admission?.expiresAtMillis }.minOrNull()

    fun status(rowId: String): AgentRequestStatus {
        val request = pending[rowId]
        return when {
            request?.admission != null -> AgentRequestStatus.AwaitingAgent
            request != null -> AgentRequestStatus.Sending
            rowId in unconfirmed -> AgentRequestStatus.Unconfirmed
            else -> AgentRequestStatus.Idle
        }
    }

    fun begin(
        rowId: String,
        agent: String,
    ) {
        unconfirmed.remove(rowId)
        pending[rowId] = Pending(agent)
    }

    fun completeAdmission(
        rowId: String,
        admission: AgentRequestAdmission?,
    ) {
        val request = pending[rowId] ?: return
        if (admission == null) pending.remove(rowId) else pending[rowId] = request.copy(admission = admission)
    }

    fun clear() {
        pending.clear()
        unconfirmed.clear()
    }

    fun expire(nowMillis: Long) {
        pending.toMap().forEach { (rowId, request) ->
            val admission = request.admission ?: return@forEach
            val expiry = admission.expiresAtMillis ?: return@forEach
            if (expiry <= nowMillis) {
                pending.remove(rowId)
                unconfirmed[rowId] = admission.eventId
            }
        }
    }

    fun reconcileHistory(
        history: AgentNativeRequestHistory,
        targets: List<AgentRequestTarget>,
    ) {
        pending.toMap().forEach { (rowId, request) ->
            val id = request.admission?.eventId ?: return@forEach
            if (history.concluded(request.agent, id)) pending.remove(rowId)
        }
        targets.forEach { target ->
            val unconfirmedId = unconfirmed[target.rowId]
            if (unconfirmedId != null && history.concluded(target.agent, unconfirmedId)) {
                unconfirmed.remove(target.rowId)
            }
            if (target.rowId !in pending) {
                val admission = history.outstanding(target) ?: return@forEach
                if (admission.eventId != unconfirmed[target.rowId]) {
                    pending[target.rowId] = Pending(target.agent, admission)
                }
            }
        }
        expire(System.currentTimeMillis())
    }

    fun reconcile(rows: Sequence<TimelineMessageRecordFfi>) {
        if (!hasAdmittedRequests) return
        rows.forEach { row -> confirm(row.sender, row.agentSettlements()) }
    }

    fun confirm(
        sender: String,
        eventIds: Sequence<String>,
    ) {
        val confirmed = eventIds.toSet()
        if (confirmed.isEmpty()) return
        pending.toMap().forEach { (rowId, request) ->
            val id = request.admission?.eventId
            if (id != null && request.agent == sender && id in confirmed) pending.remove(rowId)
        }
    }

    fun reject(ids: Set<String>): Boolean {
        val failed = pending.entries.filter { it.value.admission?.eventId in ids }.map { it.key }
        failed.forEach(pending::remove)
        return failed.isNotEmpty()
    }
}

private fun TimelineMessageRecordFfi.agentSettlements(): Sequence<String> {
    if (deleted || invalidationStatus != null) return emptySequence()
    return when (kind) {
        MARMOT_CHAT_EVENT_KIND, THREAD_MESSAGE_KIND -> tags.agentSettlementIds()
        else -> agentItem?.settles.orEmpty().asSequence()
    }
}

internal fun ConversationController.retainedAgentRequestProgress(): AgentRequestProgress =
    appState.retainedConversationStates.agentRequestProgress(conversationAccountRef, group.groupIdHex)
