package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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

/**
 * Sends one item version's answers and controls. Its progress lives in the conversation's [progress]
 * map under [key], so a row that scrolls away and back mid-send keeps its in-flight guard.
 */
@Stable
internal class AgentRequestSender(
    private val progress: MutableMap<String, AgentRequestStatus>,
    private val key: String,
    private val start: (AgentRequest, (SendAcceptDispositionFfi?) -> Unit) -> Unit,
) {
    /** Progress of the last request sent for this item version. */
    val status: AgentRequestStatus get() = progress[key] ?: AgentRequestStatus.Idle

    /** Sends [request] unless one for this item version is still on its way. */
    fun send(request: AgentRequest) {
        if (status == AgentRequestStatus.Sending) return
        progress[key] = AgentRequestStatus.Sending
        start(request) { outcome -> progress[key] = outcome.requestStatus() }
    }
}

/** Shared by the inline requests and the activity sheet for one conversation. */
internal val LocalAgentRequestProgress =
    compositionLocalOf<MutableMap<String, AgentRequestStatus>> { mutableStateMapOf() }

@Composable
internal fun rememberAgentRequestProgress(conversationKey: Any) =
    remember(conversationKey) {
        mutableStateMapOf<String, AgentRequestStatus>()
    }

/**
 * Sends the row's requests to the agent that wrote [item]. Keyed by the item's version, so the
 * agent's next version, which settles the request, starts from no progress.
 */
@Composable
internal fun rememberAgentRequestSender(
    item: AgentItem,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): AgentRequestSender {
    val progress = LocalAgentRequestProgress.current
    return remember(progress, controller, item.rowId, item.version, item.agentPubkeyHex) {
        AgentRequestSender(progress, "${item.rowId}:${item.version}") { request, onCompleted ->
            appState.launchMutation {
                var outcome: SendAcceptDispositionFfi? = null
                try {
                    outcome = controller.sendAgentRequest(item.agentPubkeyHex, item.rowId, request)
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
