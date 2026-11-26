@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.AgentItemStateFfi
import dev.ipf.marmotkit.MessageUpdateFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AGENT_SETTLEMENT_KINDS
import dev.ipf.whitenoise.android.state.AgentRequestProgress
import dev.ipf.whitenoise.android.state.AgentRequestTarget
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TransientNoticeKind
import dev.ipf.whitenoise.android.state.agentNativeRequestHistory
import dev.ipf.whitenoise.android.state.agentSettlementIds
import dev.ipf.whitenoise.android.state.ownAgentRequests
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native history carries durable requests and confirmations superseded by later item edits. */
@Composable
internal fun ObserveAgentSettlements(
    controller: ConversationController,
    progress: AgentRequestProgress,
    rows: List<TimelineMessageRecordFfi>,
) {
    val account = controller.conversationAccountRef ?: return
    val group = controller.group.groupIdHex
    val targets = remember(rows) { rows.mapNotNull { it.requestTarget() } }
    val admitted = progress.hasAdmittedRequests
    if (admitted) SideEffect { progress.reconcile(rows.asSequence()) }
    LaunchedEffect(controller, targets, controller.groupRecoveryStatus, admitted) {
        if (targets.isEmpty() && !admitted) return@LaunchedEffect
        val ids = progress.admittedEventIds
        val native =
            runCatchingCancellable {
                controller.appState.marmotIo { agentNativeRequestHistory(account, group, targets, ids) }
            }.getOrNull() ?: return@LaunchedEffect
        progress.reconcileHistory(native, targets)
        progress.reconcile(rows.asSequence())
    }
    val expiry = progress.nextExpiryMillis
    LaunchedEffect(progress, expiry) {
        if (expiry != null) {
            delay((expiry - System.currentTimeMillis()).coerceAtLeast(0))
            progress.expire(System.currentTimeMillis().coerceAtLeast(expiry))
        }
    }
}

/** A later edit can replace a settlement in the timeline before Compose observes it. */
@Composable
internal fun ObserveAgentSettlementEvents(
    controller: ConversationController,
    progress: AgentRequestProgress,
) {
    val account = controller.conversationAccountRef ?: return
    val group = controller.group.groupIdHex
    val admitted = progress.hasAdmittedRequests
    LaunchedEffect(controller, account, group, admitted) {
        if (!admitted) return@LaunchedEffect
        launch { observeAgentDeliveryFailures(controller, progress, account, group) }
        runCatchingCancellable {
            val stream =
                withContext(NonCancellable) {
                    controller.appState.marmotIo { subscribeMessages(account, group, 1u, AGENT_SETTLEMENT_KINDS) }
                }
            stream.use {
                stream.snapshot().filterNot { it.invalidated }.forEach {
                    progress.confirm(it.sender, it.tags.agentSettlementIds())
                }
                while (true) {
                    val event = (stream.next() as? MessageUpdateFfi.Message)?.received?.message ?: break
                    progress.confirm(event.sender, event.tags.agentSettlementIds())
                }
            }
        }
    }
}

/** Raw subscriptions carry received events only. A local rejection leaves an invalidated request. */
private suspend fun observeAgentDeliveryFailures(
    controller: ConversationController,
    progress: AgentRequestProgress,
    account: String,
    group: String,
) {
    while (progress.hasAdmittedRequests) {
        delay(AGENT_DELIVERY_CHECK_MILLIS)
        val ids = progress.admittedEventIds
        val failed =
            runCatchingCancellable {
                controller.appState.marmotIo {
                    ownAgentRequests(account, group)
                        .filter { it.invalidated && it.messageIdHex in ids }
                        .map { it.messageIdHex }
                        .toSet()
                }
            }.getOrNull().orEmpty()
        if (progress.reject(failed)) {
            controller.presentConversationTransient(R.string.agent_request_failed, TransientNoticeKind.Information)
        }
    }
}

private fun TimelineMessageRecordFfi.requestTarget(): AgentRequestTarget? {
    val item = agentItem?.takeUnless { deleted || invalidationStatus != null } ?: return null
    val startedAt = timelineAt.takeIf { item.itemType == "session" }
    val canAct =
        when (item.itemType) {
            "session" -> true
            "turn", "approval", "question" -> item.state in OPEN_STATES
            else -> false
        }
    return AgentRequestTarget(messageIdHex, sender, startedAt).takeIf { canAct }
}

private val OPEN_STATES = setOf(AgentItemStateFfi.RUNNING, AgentItemStateFfi.WAITING)
private const val AGENT_DELIVERY_CHECK_MILLIS = 15_000L
