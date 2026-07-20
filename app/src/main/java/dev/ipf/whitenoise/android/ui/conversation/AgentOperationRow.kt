@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AgentOperationPresentation

private val FAILURE_STATUSES = setOf("failed", "error", "cancelled", "canceled")
private val SUCCESS_STATUSES = setOf("completed", "complete", "finished", "success", "succeeded", "done")

@Composable
internal fun AgentOperationRow(
    messageId: String,
    operation: AgentOperationPresentation,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    AgentOperationChip(
        operation = operation,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    )
}

/** Compact tool-call chip with full wire details available on demand. */
@Composable
internal fun AgentOperationChip(
    operation: AgentOperationPresentation,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = agentOperationStatusColor(operation)
    val toggleLabel =
        stringResource(
            if (expanded) {
                R.string.group_system_hide_details
            } else {
                R.string.group_system_show_details
            },
        )
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (operation.canExpand) {
                        Modifier.clickable(
                            onClickLabel = toggleLabel,
                            role = Role.Button,
                            onClick = onToggle,
                        )
                    } else {
                        Modifier
                    },
                ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AgentOperationHeader(
                operation = operation,
                expanded = expanded,
                statusColor = statusColor,
            )
            Text(
                text = operation.collapsedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                AgentOperationExpandedDetails(operation)
            }
        }
    }
}

@Composable
private fun AgentOperationHeader(
    operation: AgentOperationPresentation,
    expanded: Boolean,
    statusColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = "⚙", style = MaterialTheme.typography.labelLarge)
        Text(
            text = operation.name ?: operation.eventType.orEmpty(),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AgentOperationStatus(operation = operation, color = statusColor)
        if (operation.canExpand) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AgentOperationExpandedDetails(operation: AgentOperationPresentation) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    operation.preview?.let { preview ->
        AgentOperationDetail(
            label = stringResource(R.string.preview),
            value = preview,
        )
    }
    operation.argumentsJson?.let { arguments ->
        AgentOperationDetail(
            label = stringResource(R.string.details),
            value = arguments,
        )
    }
    completionSummary(operation)?.let { summary ->
        AgentOperationDetail(
            label = stringResource(R.string.status),
            value = summary,
        )
    }
}

@Composable
private fun AgentOperationStatus(
    operation: AgentOperationPresentation,
    color: Color,
) {
    val label = operation.status ?: operation.ok?.let { if (it) "✓" else "✕" }
    if (label != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "●", color = color, style = MaterialTheme.typography.labelSmall)
            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AgentOperationDetail(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun agentOperationStatusColor(operation: AgentOperationPresentation): Color {
    val status = operation.status?.lowercase()
    return when {
        operation.ok == false -> MaterialTheme.colorScheme.error
        operation.ok == true -> MaterialTheme.colorScheme.primary
        status in FAILURE_STATUSES -> MaterialTheme.colorScheme.error
        status in SUCCESS_STATUSES -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun completionSummary(operation: AgentOperationPresentation): String? =
    buildList {
        operation.status?.let(::add)
        operation.ok?.let { add(if (it) "✓" else "✕") }
        operation.durationMs?.let { add("$it ms") }
    }.joinToString(" · ").takeIf(String::isNotEmpty)
