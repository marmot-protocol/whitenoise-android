package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentRequestEncoder

/**
 * Sends an answer or a control to the agent [agentPubkeyHex] in this chat.
 *
 * The agent settles the request by publishing a new version of the item, or a `refused` notice
 * that says why it did not act. Returns null when nothing was sent, after showing the failure.
 * Every native or lock failure is reported to the person; cancellation is rethrown.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ConversationController.sendAgentRequest(
    agentPubkeyHex: String,
    targetFirstEventId: String?,
    request: AgentRequest,
): SendAcceptDispositionFfi? {
    val account = boundAccountRef?.takeIf { canSendMessages && agentPubkeyHex.isNotBlank() } ?: return null
    val event = AgentRequestEncoder.encode(agentPubkeyHex, targetFirstEventId, request)
    return try {
        val summary =
            appState.withGroupCommitLock(account, group.groupIdHex) {
                check(canSendMessages)
                appState.marmotIo {
                    sendCustomEvent(account, group.groupIdHex, event.kind, event.tags, event.content)
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
