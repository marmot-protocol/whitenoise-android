@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.usesDirectTranscriptChrome
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar

/**
 * One agent item in the transcript, at the latest version MDK holds.
 *
 * Items sit in the agent's column beside its message bubbles: indented past the avatar gutter in
 * group chats, flush in direct chats. The agent's words stay ordinary bubbles; this row draws what
 * the agent did and what it asks. The timeline calls this only for items that need attention.
 */
@Composable
internal fun AgentItemTimelineRow(
    agentItem: AgentItem,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    context: AgentItemRowContext,
) {
    val agentName = appState.displayName(agentItem.agentPubkeyHex)
    if (agentItemLayout(agentItem) == AgentItemLayout.Session) {
        AgentSessionDivider(agentItem, agentName)
        return
    }
    val groupChrome =
        GroupProjector.shouldShowTranscriptSenderAvatar(isDm = controller.usesDirectTranscriptChrome, mine = false)
    val requests = rememberAgentRequestSender(agentItem, controller, appState)
    val actions =
        AgentItemActions(
            agentName = agentName,
            enabled = !context.readOnly,
            status = requests.status,
            onSend = requests::send,
        )
    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (groupChrome) AgentAvatarGutter else 0.dp, end = AgentTrailingGutter),
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
            AgentItemContent(agentItem, actions, Modifier.widthIn(max = AgentContentMaxWidth))
        }
        if (groupChrome && context.showSenderAvatar) {
            Box(Modifier.align(Alignment.BottomStart)) {
                Avatar(
                    title = agentName,
                    seed = agentItem.agentPubkeyHex,
                    size = 30.dp,
                    pictureUrl = appState.contactAvatarSource(agentItem.agentPubkeyHex),
                )
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
        AgentItemLayout.Activity -> AgentActivityLine(item, modifier)
    }
}

/** 30 dp avatar slot plus its 6 dp gap, matching incoming bubbles in group chats. */
private val AgentAvatarGutter = 36.dp
private val AgentTrailingGutter = 24.dp
private val AgentContentMaxWidth = 560.dp
