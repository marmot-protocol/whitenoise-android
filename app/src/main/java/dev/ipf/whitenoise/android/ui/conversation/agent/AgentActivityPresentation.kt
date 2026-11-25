package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.core.agent.AgentNoticeKind
import dev.ipf.whitenoise.android.core.agent.AgentToolKind
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Check
import dev.ipf.whitenoise.android.ui.icons.filled.Code
import dev.ipf.whitenoise.android.ui.icons.filled.Description
import dev.ipf.whitenoise.android.ui.icons.filled.Edit
import dev.ipf.whitenoise.android.ui.icons.filled.ErrorOutline
import dev.ipf.whitenoise.android.ui.icons.filled.Group
import dev.ipf.whitenoise.android.ui.icons.filled.Help
import dev.ipf.whitenoise.android.ui.icons.filled.Info
import dev.ipf.whitenoise.android.ui.icons.filled.Language
import dev.ipf.whitenoise.android.ui.icons.filled.Refresh
import dev.ipf.whitenoise.android.ui.icons.filled.Schedule
import dev.ipf.whitenoise.android.ui.icons.filled.Search
import dev.ipf.whitenoise.android.ui.icons.filled.Settings
import dev.ipf.whitenoise.android.ui.icons.filled.WarningAmber
import dev.ipf.whitenoise.android.ui.icons.outlined.Block
import dev.ipf.whitenoise.android.ui.icons.outlined.ChatBubbleOutline

/** The glyph for a line: what sort of step it is, or its outcome for an answered request. */
internal fun agentActivityIcon(item: AgentItem): ImageVector =
    when (val body = item.body) {
        is AgentItemBody.Tool -> toolIcon(body.kind)
        is AgentItemBody.Reasoning -> Icons.Outlined.ChatBubbleOutline
        is AgentItemBody.Task -> Icons.Default.Group
        is AgentItemBody.Notice -> noticeIcon(body.kind)
        is AgentItemBody.Error -> Icons.Default.ErrorOutline
        is AgentItemBody.Approval, is AgentItemBody.Question -> answeredIcon(item.state)
        is AgentItemBody.Plan -> Icons.Default.Check
        else -> Icons.Default.Info
    }

private fun toolIcon(kind: AgentToolKind): ImageVector =
    when (kind) {
        AgentToolKind.Command -> Icons.Default.Code
        AgentToolKind.Edit -> Icons.Default.Edit
        AgentToolKind.Read -> Icons.Default.Description
        AgentToolKind.Search -> Icons.Default.Search
        AgentToolKind.Fetch -> Icons.Default.Language
        AgentToolKind.Subagent -> Icons.Default.Group
        AgentToolKind.Mcp, AgentToolKind.Other -> Icons.Default.Settings
    }

private fun noticeIcon(kind: AgentNoticeKind): ImageVector =
    when (kind) {
        AgentNoticeKind.Waiting -> Icons.Default.Schedule
        AgentNoticeKind.Retry -> Icons.Default.Refresh
        AgentNoticeKind.Warning -> Icons.Default.WarningAmber
        AgentNoticeKind.Refused -> Icons.Outlined.Block
        AgentNoticeKind.CommandOutput -> Icons.Default.Code
        AgentNoticeKind.ModelChanged -> Icons.Default.Settings
        AgentNoticeKind.Compaction, AgentNoticeKind.Other -> Icons.Default.Info
    }

private fun answeredIcon(state: AgentItemState): ImageVector =
    when (state) {
        AgentItemState.Done -> Icons.Default.Check
        AgentItemState.Declined -> Icons.Outlined.Block
        AgentItemState.Cancelled -> Icons.Default.Schedule
        else -> Icons.Default.Help
    }

/** Commands, paths and queries read as code; prose stays in the body font. */
internal fun agentActivityMonospace(item: AgentItem): Boolean =
    when (val body = item.body) {
        is AgentItemBody.Tool -> body.kind != AgentToolKind.Subagent
        is AgentItemBody.Approval -> true
        else -> false
    }

/**
 * The line's title. The agent's own title ("Ran a command") reads well for steps; an answered
 * request and a refusal say what happened in the viewer's terms instead.
 */
@Composable
internal fun agentActivityTitle(item: AgentItem): String =
    when (val body = item.body) {
        is AgentItemBody.Approval -> approvalTitle(item, body)
        is AgentItemBody.Question -> questionTitle(item, body)
        is AgentItemBody.Notice ->
            when (body.reason) {
                "not_allowed" -> stringResource(R.string.agent_refused_not_allowed)
                "busy" -> stringResource(R.string.agent_refused_busy)
                else -> item.title
            }
        else -> item.title
    }.ifBlank {
        item.text
            .lineSequence()
            .firstOrNull()
            .orEmpty()
    }

@Composable
private fun approvalTitle(
    item: AgentItem,
    approval: AgentItemBody.Approval,
): String =
    when (item.state) {
        AgentItemState.Cancelled -> stringResource(R.string.agent_approval_expired)
        AgentItemState.Declined -> stringResource(R.string.agent_approval_declined)
        else ->
            approval.choices
                .firstOrNull { it.id == approval.answeredChoiceId }
                ?.let { stringResource(R.string.agent_approval_answered, it.label) }
                ?: item.title
    }

@Composable
private fun questionTitle(
    item: AgentItem,
    question: AgentItemBody.Question,
): String {
    val answers = question.answers
    return when {
        item.state == AgentItemState.Cancelled -> stringResource(R.string.agent_question_closed)
        answers != null ->
            stringResource(R.string.agent_question_answered, agentAnswerSummary(question.questions, answers))
        else -> item.title
    }
}
