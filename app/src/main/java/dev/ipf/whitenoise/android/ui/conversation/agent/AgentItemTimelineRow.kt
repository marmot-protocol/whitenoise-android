@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.usesDirectTranscriptChrome
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ExpandLess
import dev.ipf.whitenoise.android.ui.icons.filled.ExpandMore

/**
 * One marmot-agents item in the transcript, at its latest version.
 *
 * Items sit in the agent's column beside its message bubbles: indented past the avatar gutter in
 * group chats, flush in direct chats. The agent's words stay ordinary bubbles; this row draws what
 * the agent did and what it asks.
 */
@Composable
internal fun AgentItemTimelineRow(
    item: TimelineMessage,
    agentItem: AgentItem,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    context: AgentItemRowContext,
) {
    val record = item.record
    val agentName = appState.displayName(record.sender)
    if (agentItemLayout(agentItem) == AgentItemLayout.Session && item.agentActivity.isEmpty()) {
        AgentSessionDivider(agentItem, agentName)
        return
    }
    val groupChrome =
        GroupProjector.shouldShowTranscriptSenderAvatar(isDm = controller.usesDirectTranscriptChrome, mine = false)
    val requests = rememberAgentRequestSender(agentItem, record.sender, controller, appState)
    val actions =
        AgentItemActions(
            agentName = agentName,
            enabled = !context.readOnly,
            status = requests.status,
            onSend = requests::send,
        )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = if (groupChrome) AgentAvatarGutter else 0.dp,
                    end = AgentTrailingGutter,
                    top = 2.dp,
                    bottom = 2.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (groupChrome && context.showSenderName) {
            EmojiLabel(
                text = agentName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        val contentModifier = Modifier.widthIn(max = AgentContentMaxWidth)
        if (item.agentActivity.isEmpty() && !item.agentWaitingForInput) {
            AgentItemContent(agentItem, actions, contentModifier)
        } else {
            AgentTurnActivity(agentItem, actions, item, contentModifier)
        }
    }
}

@Composable
private fun AgentTurnActivity(
    item: AgentItem,
    actions: AgentItemActions,
    row: TimelineMessage,
    modifier: Modifier,
) {
    var expanded by rememberSaveable(item.firstEventId) { mutableStateOf(false) }
    val toggle: @Composable () -> Unit = {
        if (row.agentActivity.isNotEmpty()) {
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.agent_activity_hide else R.string.agent_activity_show,
                        ),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    Column(modifier) {
        if (agentItemLayout(item) == AgentItemLayout.Turn) {
            AgentTurnLine(item, actions, waitingForInput = row.agentWaitingForInput, trailingContent = toggle)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.agent_activity_label),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                toggle()
            }
        }
        if (expanded) {
            row.agentActivity.forEach { detail ->
                androidx.compose.runtime.key(detail.firstEventId) {
                    AgentItemContent(detail, actions.copy(enabled = false))
                }
            }
        }
    }
}

/** Draws [item] in the layout its type and state call for. Shared by the transcript and previews. */
@Composable
internal fun AgentItemContent(
    item: AgentItem,
    actions: AgentItemActions,
    modifier: Modifier = Modifier,
) {
    when (agentItemLayout(item)) {
        AgentItemLayout.Turn -> AgentTurnLine(item, actions, modifier)
        AgentItemLayout.Session -> AgentSessionDivider(item, actions.agentName, modifier)
        AgentItemLayout.ApprovalCard -> AgentApprovalCard(item, actions, modifier)
        AgentItemLayout.QuestionCard -> AgentQuestionCard(item, actions, modifier)
        AgentItemLayout.Plan -> AgentPlanCard(item, modifier)
        AgentItemLayout.Activity -> AgentActivityLine(item, modifier)
    }
}

/** 30 dp avatar slot plus its 6 dp gap, matching incoming bubbles in group chats. */
private val AgentAvatarGutter = 36.dp
private val AgentTrailingGutter = 24.dp
private val AgentContentMaxWidth = 560.dp
