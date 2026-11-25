package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentRequestEncoder

/**
 * Sends an answer or a control to the agent [agentPubkeyHex] in this chat.
 *
 * [targetRowId] is the row of the item the request answers or stops. The agent settles the request
 * with a new version of that item, or with a `refused` notice that says why it did not act. Returns null when nothing was sent, after showing the failure.
 * A chat that cannot send, and every native or lock failure, is reported to the person;
 * cancellation is rethrown.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ConversationController.sendAgentRequest(
    agentPubkeyHex: String,
    targetRowId: String,
    request: AgentRequest,
): SendAcceptDispositionFfi? {
    val event = AgentRequestEncoder.encode(agentPubkeyHex, targetRowId, request)
    // The chat and account are fixed before the first suspension and checked again under the lock.
    val groupId = group.groupIdHex
    return try {
        val account = checkNotNull(boundAccountRef?.takeIf { canSendMessages && agentPubkeyHex.isNotBlank() })
        val summary =
            appState.withGroupCommitLock(account, groupId) {
                check(canSendMessages && boundAccountRef == account && group.groupIdHex == groupId)
                appState.marmotIo {
                    sendCustomEvent(account, groupId, event.kind, event.tags, event.content)
                }
            }
        Log.i("WNAgents", "event=request_result outcome=${summary.acceptDisposition.name} action=${request.logName}")
        summary.acceptDisposition
    } catch (throwable: Throwable) {
        rethrowIfCancellation(throwable)
        Log.i("WNAgents", "event=request_result outcome=failed action=${request.logName}")
        appState.presentFailure(R.string.agent_request_failed, "AGENT_REQUEST", throwable)
        null
    }
}

private val AgentRequest.logName: String
    get() =
        when (this) {
            is AgentRequest.Choice -> "choice"
            is AgentRequest.Questions -> "questions"
            is AgentRequest.Interrupt -> "interrupt"
        }
