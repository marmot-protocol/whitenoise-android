@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentPlanEntry
import dev.ipf.whitenoise.android.core.agent.AgentPlanEntryState
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.CheckCircle
import dev.ipf.whitenoise.android.ui.icons.filled.RadioButtonUnchecked

/** The agent's plan, updated in place as it works through the steps. */
@Composable
internal fun AgentPlanCard(
    item: AgentItem,
    modifier: Modifier = Modifier,
) {
    val plan = item.body as? AgentItemBody.Plan ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.title.ifBlank { stringResource(R.string.agent_plan_title) },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            plan.text?.takeIf(String::isNotBlank)?.let { text ->
                Text(
                    text = remember(text) { agentPlainPlanText(text) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            plan.entries.forEach { entry -> AgentPlanEntryRow(entry) }
        }
    }
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
        val glyph = Modifier.padding(top = 2.dp).size(16.dp)
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
            style = MaterialTheme.typography.bodyMedium,
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
