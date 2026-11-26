package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.MessageUpdateFfi
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemReader
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Uses authenticated senders of retained requests in the open activity window. */
@Composable
internal fun rememberAgentAnswerAttribution(
    controller: ConversationController,
    rows: List<TimelineMessage>,
): Map<String, String> {
    val replies = remember(rows) { rows.mapNotNull(::settledAnswer) }
    val account = controller.conversationAccountRef
    val group = controller.group.groupIdHex
    return produceState(emptyMap<String, String>(), controller, account, group, replies) {
        value = emptyMap()
        if (account != null && replies.isNotEmpty()) {
            runCatchingCancellable {
                val stream =
                    withContext(NonCancellable) {
                        controller.appState.marmotIo {
                            subscribeMessages(account, group, null, listOf(AgentItemReader.REQUEST_KIND))
                        }
                    }
                stream.use {
                    val requestedIds = replies.flatMap { it.requests }.toSet()
                    val matching =
                        controller.appState.marmotIo {
                            stream
                                .snapshot()
                                .mapNotNull { it.answerIn(requestedIds) }
                                .associateByTo(mutableMapOf()) { it.id }
                        }
                    value = findAnswerAttribution(replies, matching)
                    while (true) {
                        val event = (stream.next() as? MessageUpdateFfi.Message)?.received?.message ?: break
                        if (event.messageIdHex in requestedIds) {
                            matching[event.messageIdHex] = AnswerEvent(event.messageIdHex, event.sender, event.tags)
                            value = findAnswerAttribution(replies, matching)
                        }
                    }
                }
            }
        }
    }.value
}

private data class SettledAnswer(
    val rowId: String,
    val agent: String,
    val requests: List<String>,
    val action: String,
)

private data class AnswerEvent(
    val id: String,
    val sender: String,
    val tags: List<MessageTagFfi>,
)

private fun AppMessageRecordFfi.answerIn(requestedIds: Set<String>): AnswerEvent? =
    if (invalidated || messageIdHex !in requestedIds) null else AnswerEvent(messageIdHex, sender, tags)

private fun settledAnswer(row: TimelineMessage): SettledAnswer? {
    val item =
        row.projected
            ?.takeUnless { it.deleted }
            ?.agentItem
            ?.takeUnless { it.alerts || it.settles.isEmpty() } ?: return null
    val action =
        when (val body = AgentItemReader.read(row.record, row.projected)?.body) {
            is AgentItemBody.Approval -> "choice".takeIf { body.answeredChoiceId != null }
            is AgentItemBody.Question -> "questions".takeIf { body.answers != null }
            else -> null
        }
    return action?.let { SettledAnswer(row.id, row.record.sender, item.settles, it) }
}

private fun findAnswerAttribution(
    replies: List<SettledAnswer>,
    requests: Map<String, AnswerEvent>,
): Map<String, String> =
    replies
        .mapNotNull { reply ->
            reply.requests
                .mapNotNull(requests::get)
                .filter { request -> request.isAnswerTo(reply) }
                .map { it.sender }
                .distinct()
                .singleOrNull()
                ?.let { reply.rowId to it }
        }.toMap()

private fun AnswerEvent.isAnswerTo(reply: SettledAnswer): Boolean {
    val targets = tags.filter { it.values.firstOrNull() == "e" }
    val agents = tags.filter { it.values.firstOrNull() == "p" }
    val addressesReply =
        targets.singleOrNull()?.values?.getOrNull(1) == reply.rowId &&
            agents.singleOrNull()?.values?.getOrNull(1) == reply.agent
    if (!addressesReply) return false
    val details = tags.singleOrNull { it.values.firstOrNull() == "details" }?.values?.getOrNull(1) ?: return false
    return runCatching { JSONObject(details).optString("action") == reply.action }.getOrDefault(false)
}
