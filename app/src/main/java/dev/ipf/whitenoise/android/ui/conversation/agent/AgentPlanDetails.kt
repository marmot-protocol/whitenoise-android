@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentPlanEntry
import dev.ipf.whitenoise.android.core.agent.AgentPlanEntryState
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.CheckCircle
import dev.ipf.whitenoise.android.ui.icons.filled.RadioButtonUnchecked

/** The opened part of a plan line: the plan's text and its steps with their progress. */
@Composable
internal fun AgentPlanDetails(plan: AgentItemBody.Plan) {
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
