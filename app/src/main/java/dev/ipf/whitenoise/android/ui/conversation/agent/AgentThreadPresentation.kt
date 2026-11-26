@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.isAgentOwnThreadMessage
import dev.ipf.whitenoise.android.core.agent.isAgentThreadRow
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody

internal val LocalOpenAgentThread = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal fun agentThreadDestination(
    row: TimelineMessageRecordFfi?,
    timeline: () -> List<TimelineMessage>,
): String? {
    val item = row?.takeIf { it.isAgentThreadRow() } ?: return null
    val known =
        if (item.agentItemFirstEventMissing) {
            timeline().mapNotNull { it.projected }.associateBy { it.messageIdHex }
        } else {
            emptyMap()
        }
    return item.agentThreadTask(known)
}

/** Agent items and words point to a task. For people's replies, follow the loaded parent chain. */
internal fun TimelineMessageRecordFfi.agentThreadTask(rowsById: Map<String, TimelineMessageRecordFfi>): String? {
    val placement = thread ?: return null
    val root = placement.rootMessageIdHex
    return when {
        agentItemFirstEventMissing -> heldAgentTask(root, rowsById.values)
        hasAgentTaskParent() -> placement.parentMessageIdHex
        else -> taskAncestor(root, placement.parentMessageIdHex, rowsById)
    }
}

/** An edit can arrive before the original's parent tags. The producer still names its owning task. */
private fun TimelineMessageRecordFfi.heldAgentTask(
    root: String,
    rows: Collection<TimelineMessageRecordFfi>,
): String? {
    val itemId = tags.singleOrNull { it.values.firstOrNull() == "subagent" }?.values?.getOrNull(1) ?: return root
    return rows
        .firstOrNull { row ->
            row.sender == sender &&
                row.agentItem?.itemType == "task" &&
                row.agentItem?.itemId == itemId &&
                (row.messageIdHex == root || row.thread?.rootMessageIdHex == root)
        }?.messageIdHex
}

private fun TimelineMessageRecordFfi.taskAncestor(
    root: String,
    parent: String,
    rowsById: Map<String, TimelineMessageRecordFfi>,
): String? {
    val seen = hashSetOf(messageIdHex)
    return generateSequence(parent) { id ->
        rowsById[id]?.thread?.takeIf { it.rootMessageIdHex == root }?.parentMessageIdHex
    }.takeWhile(seen::add).firstNotNullOfOrNull { id ->
        val row = rowsById[id]
        when {
            id == root || row?.agentItem?.itemType == "task" -> id
            row?.hasAgentTaskParent() == true -> row.thread?.takeIf { it.rootMessageIdHex == root }?.parentMessageIdHex
            else -> null
        }
    }
}

private fun TimelineMessageRecordFfi.hasAgentTaskParent(): Boolean = agentItem != null || isAgentOwnThreadMessage()

@Composable
internal fun AgentThreadLink(taskId: String) {
    val open = LocalOpenAgentThread.current ?: return
    TextButton(onClick = { open(taskId) }) { Text(stringResource(R.string.agent_thread_open)) }
}

@Composable
internal fun AgentThreadMessage(
    row: TimelineMessageRecordFfi,
    senderName: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            senderName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MarkdownMessageBody(document = row.contentTokens)
    }
}
