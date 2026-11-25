@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentFileChange
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.core.agent.AgentPlanEntry
import dev.ipf.whitenoise.android.core.agent.AgentPlanEntryState
import dev.ipf.whitenoise.android.core.formatAgentOperationArguments
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.CheckCircle
import dev.ipf.whitenoise.android.ui.icons.filled.RadioButtonUnchecked


/** The opened part of an activity line: output, diffs, a plan, thinking or error details. */
@Composable
internal fun AgentActivityDetails(
    item: AgentItem,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val body = item.body) {
            is AgentItemBody.Tool -> AgentToolDetails(item, body)
            is AgentItemBody.Plan -> AgentPlanDetails(body)
            is AgentItemBody.Error -> {
                AgentProse(item.text)
                body.code?.let { AgentMeta(it.toString()) }
            }
            else -> AgentProse(item.text)
        }
    }
}

@Composable
private fun AgentToolDetails(
    item: AgentItem,
    tool: AgentItemBody.Tool,
) {
    // An edit names its files above each diff, so its path is not repeated here.
    val path = tool.path?.takeIf { tool.changes.isEmpty() }
    val subject = tool.command?.let { "$ " + agentDisplayCommand(it) } ?: path ?: tool.query
    subject?.let { AgentCodeBlock(it) }
    toolMeta(tool)?.let { AgentMeta(it) }
    val subjectInBody = tool.command != null || tool.path != null || tool.query != null
    if (!subjectInBody && item.text.lines().size > 1) {
        AgentCodeBlock(item.text)
    }
    tool.inputJson?.let { input ->
        AgentMeta(stringResource(R.string.agent_tool_input))
        AgentCodeBlock(remember(input) { formatAgentOperationArguments(input) })
    }
    tool.changes.forEach { change -> AgentFileDiff(change) }
    if (item.diffsCut > 0) {
        AgentMeta(pluralStringResource(R.plurals.agent_tool_diffs_cut, item.diffsCut, item.diffsCut))
    }
    AgentToolOutput(item, tool)
}

@Composable
private fun AgentToolOutput(
    item: AgentItem,
    tool: AgentItemBody.Tool,
) {
    // A finished edit's output is the harness confirming the write, which the diff already shows.
    // A failed one keeps its output, which says what went wrong.
    val showsOutput = tool.changes.isEmpty() || item.state == AgentItemState.Failed
    tool.output?.takeIf { it.isNotBlank() && showsOutput }?.let { output ->
        if (tool.inputJson != null) AgentMeta(stringResource(R.string.agent_tool_output))
        AgentCodeBlock(output.trimEnd())
    }
    item.outputCutLength?.let { length ->
        AgentMeta(stringResource(R.string.agent_tool_output_cut, length.toInt()))
    }
    tool.error?.let { error ->
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** Folder, exit status and duration on one line, or null when the tool reported none. */
@Composable
private fun toolMeta(tool: AgentItemBody.Tool): String? {
    val parts =
        buildList {
            tool.cwd?.let { add(stringResource(R.string.agent_tool_cwd, it)) }
            tool.exitCode?.let { add(stringResource(R.string.agent_tool_exit_code, it.toInt())) }
            tool.durationMs?.takeIf { it >= MIN_SHOWN_DURATION_MS }?.let { add(agentDurationText(it)) }
        }
    return parts.joinToString(" · ").takeIf(String::isNotEmpty)
}

@Composable
private fun AgentFileDiff(change: AgentFileChange) {
    Text(
        text = change.path,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (change.diff.isNotBlank()) {
        val added = MaterialTheme.colorScheme.onSurface
        val removed = MaterialTheme.colorScheme.error
        val quiet = MaterialTheme.colorScheme.onSurfaceVariant
        val styled =
            remember(change.diff, added, removed, quiet) {
                styledDiff(change.diff.trimEnd(), added, removed, quiet)
            }
        AgentCodeBlock(styled)
    }
}

private fun styledDiff(
    diff: String,
    added: Color,
    removed: Color,
    quiet: Color,
): AnnotatedString =
    buildAnnotatedString {
        diff.lines().forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val color =
                when (agentDiffLine(line)) {
                    AgentDiffLine.Added -> added
                    AgentDiffLine.Removed -> removed
                    AgentDiffLine.Hunk, AgentDiffLine.Context -> quiet
                }
            withStyle(SpanStyle(color = color)) { append(line) }
        }
    }

@Composable
private fun AgentCodeBlock(text: String) = AgentCodeBlock(AnnotatedString(text))

/** Monospace text in an outlined block that scrolls sideways instead of wrapping code. */
@Composable
private fun AgentCodeBlock(text: AnnotatedString) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        AgentExpandableText(
            text = text,
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            maxLines = AGENT_CODE_BLOCK_MAX_LINES,
            softWrap = false,
        )
    }
}

@Composable
private fun AgentProse(text: String) {
    if (text.isBlank()) return
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AgentMeta(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val AGENT_CODE_BLOCK_MAX_LINES = 80
private const val MIN_SHOWN_DURATION_MS = 1000L

@Composable
private fun AgentPlanDetails(plan: AgentItemBody.Plan) {
    plan.text?.takeIf(String::isNotBlank)?.let { text -> AgentProse(remember(text) { agentPlainPlanText(text) }) }
    plan.entries.forEach { entry -> AgentPlanEntryRow(entry) }
}

@Composable
private fun AgentPlanEntryRow(entry: AgentPlanEntry) {
    val description =
        stringResource(
            when (entry.state) {
                AgentPlanEntryState.Done -> R.string.agent_plan_step_done
                AgentPlanEntryState.Running -> R.string.agent_plan_step_running
                AgentPlanEntryState.Pending -> R.string.agent_plan_step_pending
            },
            entry.text,
        )
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val glyph = Modifier.padding(top = 1.dp).size(14.dp)
        when (entry.state) {
            AgentPlanEntryState.Done ->
                Icon(Icons.Default.CheckCircle, null, glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            AgentPlanEntryState.Running ->
                CircularProgressIndicator(
                    modifier = glyph.padding(2.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            AgentPlanEntryState.Pending ->
                Icon(Icons.Default.RadioButtonUnchecked, null, glyph, tint = MaterialTheme.colorScheme.outline)
        }
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (entry.state == AgentPlanEntryState.Done) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            textDecoration = if (entry.state == AgentPlanEntryState.Done) TextDecoration.LineThrough else null,
        )
    }
}
