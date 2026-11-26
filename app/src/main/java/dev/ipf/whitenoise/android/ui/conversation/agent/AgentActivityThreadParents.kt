@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import dev.ipf.marmotkit.ConversationOpenModeFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.agent.isAgentOwnThreadMessage
import dev.ipf.whitenoise.android.core.agent.isAgentThreadRow
import dev.ipf.whitenoise.android.state.CONVERSATION_WINDOW_DEFAULT_DEADLINE
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.FfiConversationWindowHandle
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Resolve native parents outside the current page only while the person browses a thread. */
@Composable
internal fun rememberAgentThreadParents(
    controller: ConversationController,
    page: TimelinePageFfi?,
    selectedTask: String?,
): Map<String, TimelineMessageRecordFfi> =
    produceState(emptyMap<String, TimelineMessageRecordFfi>(), controller, page, selectedTask) {
        value = emptyMap()
        val account = controller.conversationAccountRef ?: return@produceState
        if (page == null || selectedTask == null) return@produceState
        value =
            runCatchingCancellable {
                controller.appState.marmotIo {
                    val group = controller.group.groupIdHex
                    agentThreadParentRecords(page.messages, selectedTask) { id ->
                        retainedThreadParent(account, group, id)
                    }
                }
            }.getOrNull().orEmpty()
    }.value

internal suspend fun agentThreadParentRecords(
    rows: List<TimelineMessageRecordFfi>,
    selectedTask: String,
    find: suspend (String) -> TimelineMessageRecordFfi?,
): Map<String, TimelineMessageRecordFfi> {
    val known = rows.associateBy { it.messageIdHex }
    val additional = mutableMapOf<String, TimelineMessageRecordFfi?>()

    suspend fun lookup(id: String): TimelineMessageRecordFfi? = known[id] ?: if (id in additional) additional[id] else find(id).also { additional[id] = it }

    val replies = rows.filter { it.isAgentThreadRow() && it.agentItem == null && !it.isAgentOwnThreadMessage() }
    if (replies.isEmpty() && rows.none { it.agentItemFirstEventMissing && it.isAgentThreadRow() }) return emptyMap()
    val task = lookup(selectedTask)
    val root = task?.thread?.rootMessageIdHex ?: selectedTask
    for (reply in replies) {
        val place = reply.thread?.takeIf { it.rootMessageIdHex == root } ?: continue
        var parent = place.parentMessageIdHex
        val seen = hashSetOf(reply.messageIdHex)
        while (parent != root && seen.add(parent)) {
            val row = lookup(parent)?.takeUnless { it.agentItem != null || it.isAgentOwnThreadMessage() }
            parent = row?.thread?.takeIf { it.rootMessageIdHex == root }?.parentMessageIdHex ?: break
        }
    }
    return additional.mapNotNull { (id, row) -> row?.let { id to it } }.toMap()
}

private suspend fun MarmotInterface.retainedThreadParent(
    account: String,
    group: String,
    id: String,
): TimelineMessageRecordFfi? {
    val native =
        withContext(NonCancellable) {
            openConversationWindow(
                account,
                group,
                ConversationOpenModeFfi.MESSAGE,
                id,
                1u,
                CONVERSATION_WINDOW_DEFAULT_DEADLINE,
            )
        }
    val window = FfiConversationWindowHandle(native, release = native::close)
    return try {
        window.snapshot()?.messages?.firstOrNull { it.messageIdHex == id && it.groupIdHex == group }
    } finally {
        withContext(NonCancellable) {
            try {
                window.cancel()
            } finally {
                window.close()
            }
        }
    }
}
