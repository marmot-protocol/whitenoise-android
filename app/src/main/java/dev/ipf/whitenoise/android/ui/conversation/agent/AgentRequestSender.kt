package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.sendAgentRequest

/**
 * Local progress of the viewer's last answer or control for one item version.
 *
 * The agent is authoritative: its next version of the item settles the request and resets this
 * state. Until then the row shows that the request is on its way or was sent.
 */
internal enum class AgentRequestStatus {
    Idle,
    Sending,
    Sent,
    Unconfirmed,
}

/** What a row needs to offer actions: who the agent is, whether acting is possible, and how. */
@Stable
internal data class AgentItemActions(
    val agentName: String,
    val enabled: Boolean,
    val status: AgentRequestStatus = AgentRequestStatus.Idle,
    val onSend: (AgentRequest) -> Unit = {},
) {
    /** Buttons stay usable after a send completes, so a refused or lost request can be retried. */
    val canSend: Boolean get() = enabled && status != AgentRequestStatus.Sending
}

@Stable
internal class AgentRequestSender(
    private val start: (AgentRequest, (SendAcceptDispositionFfi?) -> Unit) -> Unit,
) {
    var status by mutableStateOf(AgentRequestStatus.Idle)
        private set

    fun send(request: AgentRequest) {
        if (status == AgentRequestStatus.Sending) return
        status = AgentRequestStatus.Sending
        start(request) { outcome -> status = outcome.requestStatus() }
    }
}

/**
 * Sends the row's requests to the agent that wrote [item], through [LocalAgentRequestTransport] when one
 * is provided. Keyed by the item's version, so a new version from the agent clears the local progress.
 */
@Composable
internal fun rememberAgentRequestSender(
    item: AgentItem,
    agentPubkeyHex: String,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): AgentRequestSender {
    val groupTransport =
        remember(controller) {
            AgentRequestTransport { agent, target, request -> controller.sendAgentRequest(agent, target, request) }
        }
    val transport = LocalAgentRequestTransport.current ?: groupTransport
    return remember(transport, item.firstEventId, item.version, agentPubkeyHex) {
        AgentRequestSender { request, onCompleted ->
            appState.launchMutation {
                var outcome: SendAcceptDispositionFfi? = null
                try {
                    val target = item.firstEventId.takeUnless { request is AgentRequest.Interrupt }
                    outcome = transport.send(agentPubkeyHex, target, request)
                } finally {
                    onCompleted(outcome)
                }
            }
        }
    }
}

private fun SendAcceptDispositionFfi?.requestStatus(): AgentRequestStatus =
    when (this) {
        SendAcceptDispositionFfi.PUBLISHED,
        SendAcceptDispositionFfi.ACCEPTED_PENDING,
        -> AgentRequestStatus.Sent
        SendAcceptDispositionFfi.COMPLETION_UNKNOWN -> AgentRequestStatus.Unconfirmed
        null -> AgentRequestStatus.Idle
    }
