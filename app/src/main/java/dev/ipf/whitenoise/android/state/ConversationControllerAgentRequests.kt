package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.AgentItemStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentRequestEncoder
import dev.ipf.whitenoise.android.core.agent.AgentRequestEvent

/**
 * Sends an answer or a control to the agent in this chat.
 *
 * An existing pending request for [target] is reused. The agent settles it with an updated
 * item, a message, or a `refused` notice that explains why it did not act.
 * Returns null when nothing was sent, after showing the failure. A chat that cannot send, and every
 * native or lock failure, is reported to the person; cancellation is rethrown.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ConversationController.sendAgentRequest(
    target: AgentRequestTarget,
    request: AgentRequest,
    token: String,
): AgentRequestAdmission? {
    val event = AgentRequestEncoder.encode(target.agent, target.rowId, request)
    // The chat and account are fixed before the first suspension and checked again under the lock.
    val groupId = group.groupIdHex
    return try {
        val account = checkNotNull(boundAccountRef?.takeIf { canSendMessages && target.agent.isNotBlank() })
        val admission =
            appState.withGroupCommitLock(account, groupId) {
                check(canSendMessages && boundAccountRef == account && group.groupIdHex == groupId)
                appState.marmotIo {
                    check(isCurrentAgentSession(account, groupId, target))
                    agentNativeRequestHistory(account, groupId, listOf(target)).outstanding(target)
                        ?: admitAgentRequest(account, groupId, target, event, token)
                }
            }
        Log.i("WNAgents", "event=request_result outcome=accepted action=${request.logName}")
        admission
    } catch (throwable: Throwable) {
        rethrowIfCancellation(throwable)
        Log.i("WNAgents", "event=request_result outcome=failed action=${request.logName}")
        appState.presentFailure(R.string.agent_request_failed, "AGENT_REQUEST", throwable)
        null
    }
}

/**
 * Admits [event] under [token] and returns the request event MDK now owns.
 *
 * When admission ends ambiguously and MDK recovers ownership of the token, the recovered summary
 * carries no event ID. The native send records the raw request before durable acceptance, so the
 * request is read back from native history instead of reporting a failure for a send MDK will deliver.
 */
internal suspend fun MarmotInterface.admitAgentRequest(
    account: String,
    groupId: String,
    target: AgentRequestTarget,
    event: AgentRequestEvent,
    token: String,
): AgentRequestAdmission {
    val accepted =
        admitLocalSend(account, groupId, token, probeExistingAdmission = false) {
            sendCustomEventWithClientToken(account, groupId, event.kind, event.tags, event.content, token)
        }
    accepted.messageIds.singleOrNull()?.let { return AgentRequestAdmission(it) }
    return checkNotNull(agentNativeRequestHistory(account, groupId, listOf(target)).outstanding(target)) {
        "MDK owns the agent request, but native history has no matching request"
    }
}

/** Prevents a control from an older activity page acting on a session the UI has since replaced. */
private fun MarmotInterface.isCurrentAgentSession(
    account: String,
    group: String,
    target: AgentRequestTarget,
): Boolean {
    if (!target.isSession) return true
    val currentSession = latestAgentSession(account, group, target.agent)
    val item = currentSession?.agentItem
    val open = item?.state == AgentItemStateFfi.RUNNING || item?.state == AgentItemStateFfi.WAITING
    return currentSession?.messageIdHex == target.rowId && open && !item?.session.isNullOrBlank()
}

internal fun MarmotInterface.latestAgentSession(
    account: String,
    group: String,
    agent: String,
): TimelineMessageRecordFfi? = readLatestAgentSession(group, agent) { timelineMessages(account, it) }

/** Both the activity sheet and request admission use authoritative native session order. */
internal fun readLatestAgentSession(
    group: String,
    agent: String,
    read: (TimelineMessageQueryFfi) -> TimelinePageFfi,
): TimelineMessageRecordFfi? {
    var query =
        TimelineMessageQueryFfi
            .page(group, null, null, null, null, null, CONVERSATION_WINDOW_MAX_ROWS)
            .copy(sender = agent)
    while (true) {
        val page = read(query)
        page.messages.lastOrNull(TimelineMessageRecordFfi::isUsableAgentSession)?.let { return it }
        val oldest = page.messages.firstOrNull()
        if (oldest == null || !page.hasMoreBefore || oldest.messageIdHex == query.beforeMessageId) return null
        query = query.copy(before = oldest.timelineAt, beforeMessageId = oldest.messageIdHex)
    }
}

internal fun TimelineMessageRecordFfi.isUsableAgentSession(): Boolean {
    val isSession = agentItem?.itemType == "session"
    return isSession && !deleted && invalidationStatus == null
}

private val AgentRequest.logName: String
    get() =
        when (this) {
            is AgentRequest.Choice -> "choice"
            is AgentRequest.Questions -> "questions"
            is AgentRequest.Interrupt -> "interrupt"
            is AgentRequest.NewSession -> "new_session"
            is AgentRequest.Set -> "set"
            is AgentRequest.Own -> "own"
        }
