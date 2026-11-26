package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.THREAD_MESSAGE_KIND

/**
 * Local progress of the viewer's answer or control. Admission and agent confirmation are separate.
 */
internal enum class AgentRequestStatus {
    Idle,
    Sending,
    AwaitingAgent,
}

/** Tracks requests across the inline rows, background activity and native row updates. */
@Stable
internal class AgentRequestProgress {
    private data class Pending(
        val agent: String,
        val admission: AgentRequestAdmission? = null,
    )

    private val pending = mutableStateMapOf<String, Pending>()

    var historyRevision by mutableLongStateOf(0L)
        private set

    val hasAdmittedRequests: Boolean get() = pending.values.any { it.admission != null }
    val admittedEventIds: Set<String> get() = pending.values.mapNotNull { it.admission?.eventId }.toSet()

    fun status(rowId: String): AgentRequestStatus {
        val request = pending[rowId]
        return when {
            request?.admission != null -> AgentRequestStatus.AwaitingAgent
            request != null -> AgentRequestStatus.Sending
            else -> AgentRequestStatus.Idle
        }
    }

    fun begin(
        rowId: String,
        agent: String,
    ) {
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
        historyRevision += 1L
    }

    fun reconcileHistory(
        history: AgentNativeRequestHistory,
        targets: List<AgentRequestTarget>,
        readRevision: Long,
    ): Boolean {
        if (readRevision != historyRevision) return false
        removeAdmissions { agent, id -> (agent to id) in history.confirmations }
        val rejected = reject(history.invalidatedIds)
        targets.forEach { target ->
            if (target.rowId !in pending) {
                history.outstanding(target)?.let { admission ->
                    pending[target.rowId] = Pending(target.agent, admission)
                }
            }
        }
        return rejected
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
        removeAdmissions { agent, id -> agent == sender && id in confirmed }
    }

    fun reject(ids: Set<String>): Boolean = ids.isNotEmpty() && removeAdmissions { _, id -> id in ids }

    private fun removeAdmissions(matches: (agent: String, eventId: String) -> Boolean): Boolean {
        val completed =
            pending.entries.mapNotNull { (rowId, request) ->
                rowId.takeIf { request.admission?.eventId?.let { matches(request.agent, it) } == true }
            }
        if (completed.isEmpty()) return false
        completed.forEach(pending::remove)
        historyRevision += 1L
        return true
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
