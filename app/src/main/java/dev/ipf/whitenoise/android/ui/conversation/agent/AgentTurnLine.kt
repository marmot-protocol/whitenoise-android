@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.state.AgentRequestStatus
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ErrorOutline

/**
 * The line at the start of a turn. While the agent works it says so and offers Stop; afterwards it
 * says how long the work took, that it failed, or that it was stopped.
 */
@Composable
internal fun AgentTurnLine(
    item: AgentItem,
    actions: AgentItemActions,
    modifier: Modifier = Modifier,
) {
    val failed = item.state == AgentItemState.Failed
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = LineMinHeight).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            item.state.isOpen ->
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            failed ->
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
        }
        Text(
            text = turnLineStatus(item),
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.state.isOpen && actions.enabled) AgentStopButton(actions)
    }
}

@Composable
private fun AgentStopButton(actions: AgentItemActions) {
    val stopping = actions.status != AgentRequestStatus.Idle
    val stopWords = stringResource(R.string.agent_turn_stop)
    val description = stringResource(R.string.agent_turn_stop_description, actions.agentName)
    TextButton(
        onClick = { actions.onSend(AgentRequest.Interrupt(words = stopWords)) },
        enabled = actions.canSend,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(
            text = if (stopping) stringResource(R.string.agent_turn_stopping) else stopWords,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** "Worked for 9s", or the failure's first line after "Didn't finish". */
@Composable
private fun turnLineStatus(item: AgentItem): String =
    listOfNotNull(
        turnStatusText(item.state, (item.body as? AgentItemBody.Turn)?.durationMs),
        item.text
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { item.state == AgentItemState.Failed },
    ).joinToString(" · ")

@Composable
private fun turnStatusText(
    state: AgentItemState,
    durationMs: Long?,
): String =
    when (state) {
        AgentItemState.Running, AgentItemState.Waiting -> stringResource(R.string.agent_turn_working)
        AgentItemState.Failed -> stringResource(R.string.agent_turn_failed)
        AgentItemState.Cancelled, AgentItemState.Declined -> stringResource(R.string.agent_turn_stopped)
        AgentItemState.Done ->
            durationMs?.let { stringResource(R.string.agent_turn_worked_for, agentDurationText(it)) }
                ?: stringResource(R.string.agent_turn_finished)
    }

/** A centred label where the agent starts a session, with the model when the session names one. */
@Composable
internal fun AgentSessionDivider(
    item: AgentItem,
    agentName: String,
    modifier: Modifier = Modifier,
) {
    val session = item.body as? AgentItemBody.Session
    val model = session?.settings?.firstOrNull { it.id == "model" }?.value
    val started = stringResource(R.string.agent_session_started, agentName)
    val label = model?.let { "$started · ${stringResource(R.string.agent_session_model, it)}" } ?: started
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp).semantics { heading() },
        )
    }
}

/** "8s", "1m 12s" or "2h 5m". */
@Composable
internal fun agentDurationText(durationMs: Long): String {
    val duration = agentDuration(durationMs)
    return when {
        duration.hours > 0 ->
            stringResource(R.string.agent_duration_hours, duration.hours.toInt(), duration.minutes.toInt())
        duration.minutes > 0 ->
            stringResource(R.string.agent_duration_minutes, duration.minutes.toInt(), duration.seconds.toInt())
        else -> stringResource(R.string.agent_duration_seconds, duration.seconds.toInt())
    }
}

private val LineMinHeight = 32.dp
