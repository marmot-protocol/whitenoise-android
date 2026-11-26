package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentRequestEncoder

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
    val targetRowId = target.rowId.takeIf { target.sessionStartedAt == null }
    val event = AgentRequestEncoder.encode(target.agent, targetRowId, request)
    // The chat and account are fixed before the first suspension and checked again under the lock.
    val groupId = group.groupIdHex
    return try {
        val account = checkNotNull(boundAccountRef?.takeIf { canSendMessages && target.agent.isNotBlank() })
        val admission =
            appState.withGroupCommitLock(account, groupId) {
                check(canSendMessages && boundAccountRef == account && group.groupIdHex == groupId)
                appState.marmotIo {
                    check(isCurrentAgentSession(account, groupId, target))
                    val waiting = agentNativeRequestHistory(account, groupId, listOf(target)).outstanding(target)
                    val now = System.currentTimeMillis()
                    if (waiting != null && (waiting.expiresAtMillis == null || waiting.expiresAtMillis > now)) {
                        waiting
                    } else {
                        val accepted =
                            admitLocalSend(account, groupId, token, probeExistingAdmission = false) {
                                sendCustomEventWithClientToken(
                                    account,
                                    groupId,
                                    event.kind,
                                    event.tags,
                                    event.content,
                                    token,
                                )
                            }
                        AgentRequestAdmission(
                            accepted.messageIds.single(),
                            target.sessionStartedAt?.let { untargetedAgentRequestDeadline(now) },
                        )
                    }
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

/** Untargeted controls operate on the current session, so validate the displayed session before admission. */
private fun MarmotInterface.isCurrentAgentSession(
    account: String,
    group: String,
    target: AgentRequestTarget,
): Boolean {
    val startedAt = target.sessionStartedAt ?: return true
    var query =
        TimelineMessageQueryFfi
            .page(group, null, null, null, null, null, CONVERSATION_WINDOW_MAX_ROWS)
            .copy(sender = target.agent, since = startedAt)
    var currentSession: String?
    do {
        val page = timelineMessages(account, query)
        currentSession =
            page.messages
                .lastOrNull {
                    it.agentItem?.itemType == "session" && !it.deleted && it.invalidationStatus == null
                }?.messageIdHex
        val oldest = page.messages.firstOrNull()
        if (oldest == null || !page.hasMoreBefore) break
        query = query.copy(before = oldest.timelineAt, beforeMessageId = oldest.messageIdHex)
    } while (currentSession == null)
    return currentSession == target.rowId
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
