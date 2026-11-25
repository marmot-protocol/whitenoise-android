@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ExpandLess
import dev.ipf.whitenoise.android.ui.icons.filled.ExpandMore

/**
 * One step of the agent's work: a tool call, its thinking, a task, a notice, an error, or an
 * approval or question that is already answered. Opens in place to show the details.
 *
 * Lines are quiet by design: secondary text, no surface, colour only for failure. A running step
 * shows a small progress ring; a finished one shows nothing extra.
 */
@Composable
internal fun AgentActivityLine(
    item: AgentItem,
    modifier: Modifier = Modifier,
) {
    val expandable = agentActivityExpandable(item)
    var expanded by rememberSaveable(item.rowId) { mutableStateOf(false) }
    val failed = item.state == AgentItemState.Failed || item.body is AgentItemBody.Error
    val toggleLabel =
        stringResource(if (expanded) R.string.group_system_hide_details else R.string.group_system_show_details)
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = if (expandable) AgentTappableLineMinHeight else AgentLineMinHeight)
                    .clip(MaterialTheme.shapes.small)
                    .then(
                        if (expandable) {
                            Modifier.clickable(onClickLabel = toggleLabel, role = Role.Button) { expanded = !expanded }
                        } else {
                            Modifier
                        },
                    ).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val accent = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(
                imageVector = agentActivityIcon(item),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            AgentActivityLabel(
                title = agentActivityTitle(item),
                subject = agentActivitySubject(item).takeIf { it != agentActivityTitle(item) }.orEmpty(),
                monospace = agentActivityMonospace(item),
                titleColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            AgentActivityTrailing(item, expandable, expanded)
        }
        if (expanded) {
            AgentActivityDetails(item, Modifier.padding(start = 28.dp, top = 2.dp, bottom = 6.dp))
        }
    }
}

@Composable
private fun AgentActivityLabel(
    title: String,
    subject: String,
    monospace: Boolean,
    titleColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subject.isNotEmpty()) {
            Text(
                text = subject,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Progress while running, the exit status of a failed command, and the open/close chevron. */
@Composable
private fun AgentActivityTrailing(
    item: AgentItem,
    expandable: Boolean,
    expanded: Boolean,
) {
    val runningLabel = stringResource(R.string.agent_item_running)
    if (item.state == AgentItemState.Running) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp).semantics { contentDescription = runningLabel },
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    agentFailureLabel(item)?.let { label ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
    }
    if (expandable) {
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun agentFailureLabel(item: AgentItem): String? {
    // The title already turns the error colour; the label adds only what the title cannot say.
    val exitCode = (item.body as? AgentItemBody.Tool)?.exitCode?.takeIf { it != 0L } ?: return null
    return stringResource(R.string.agent_tool_exit_code, exitCode.toInt())
}

private val AgentLineMinHeight = 32.dp
private val AgentTappableLineMinHeight = 48.dp
