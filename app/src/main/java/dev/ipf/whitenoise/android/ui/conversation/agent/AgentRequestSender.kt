package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.state.AgentRequestAdmission
import dev.ipf.whitenoise.android.state.AgentRequestProgress
import dev.ipf.whitenoise.android.state.AgentRequestStatus
import dev.ipf.whitenoise.android.state.AgentRequestTarget
import dev.ipf.whitenoise.android.state.AgentSessionTime
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.retainedAgentRequestProgress
import dev.ipf.whitenoise.android.state.sendAgentRequest
import java.util.UUID

/** What a row needs to offer actions: who the agent is, whether acting is possible, and how. */
@Stable
internal data class AgentItemActions(
    val agentName: String,
    val enabled: Boolean,
    val status: AgentRequestStatus = AgentRequestStatus.Idle,
    val onSend: (AgentRequest) -> Unit = {},
    val sessionControls: Boolean = false,
) {
    val canSend: Boolean get() = enabled && status.permitsSend
}

@Stable
internal class AgentRequestSender(
    private val progress: AgentRequestProgress,
    private val rowId: String,
    private val agent: String,
    private val start: (AgentRequest, String, (AgentRequestAdmission?) -> Unit) -> Unit,
) {
    val status: AgentRequestStatus get() = progress.status(rowId)

    fun send(request: AgentRequest) {
        if (!status.permitsSend) return
        val token = UUID.randomUUID().toString()
        progress.begin(rowId, agent)
        start(request, token) { eventId -> progress.completeAdmission(rowId, eventId) }
    }
}

private val AgentRequestStatus.permitsSend: Boolean
    get() = this == AgentRequestStatus.Idle || this == AgentRequestStatus.Unconfirmed

/** Shared by the inline requests and the activity sheet for one conversation. */
internal val LocalAgentRequestProgress = compositionLocalOf { AgentRequestProgress() }

@Composable
internal fun rememberAgentRequestProgress(controller: ConversationController): AgentRequestProgress {
    val account = controller.conversationAccountRef
    val group = controller.group.groupIdHex
    return remember(controller, account, group) { controller.retainedAgentRequestProgress() }
}

/** Sends the row's requests to the agent that wrote it. Item versions share the same guard. */
@Composable
internal fun rememberAgentRequestSender(
    item: AgentItem,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): AgentRequestSender {
    val progress = LocalAgentRequestProgress.current
    val sessionTime = if (item.body is AgentItemBody.Session) AgentSessionTime(item.recordedAt, item.receivedAt) else null
    val target = AgentRequestTarget(item.rowId, item.agentPubkeyHex, sessionTime)
    return remember(progress, controller, target) {
        AgentRequestSender(progress, item.rowId, item.agentPubkeyHex) { request, token, onCompleted ->
            appState.launchMutation {
                var admission: AgentRequestAdmission? = null
                try {
                    admission = controller.sendAgentRequest(target, request, token)
                } finally {
                    onCompleted(admission)
                }
            }
        }
    }
}
