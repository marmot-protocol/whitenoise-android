@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentApprovalChoice
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.state.AgentRequestStatus
import dev.ipf.whitenoise.android.ui.theme.Radii
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.outlineButtonColors

/**
 * An open approval: what the agent wants to do, and the choices the agent itself offers.
 *
 * The first choice that allows the action is the primary button; the others are text buttons.
 * Choices come from the harness, so a standing choice such as "Allow for this session" appears
 * only when the agent offers it.
 */
@Composable
internal fun AgentApprovalCard(
    item: AgentItem,
    actions: AgentItemActions,
    modifier: Modifier = Modifier,
) {
    val approval = item.body as? AgentItemBody.Approval ?: return
    AgentWaitingCard(modifier) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (item.text.isNotBlank()) {
            AgentExpandableText(
                text = AnnotatedString(item.text),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                maxLines = APPROVAL_SUBJECT_MAX_LINES,
            )
        }
        AgentRequestStatusLabel(actions)
        if (actions.enabled) AgentApprovalChoices(approval.choices, actions)
    }
}

/**
 * Two short choices sit side by side, the safe one first. Longer or more choices stack at full
 * width with the primary first, so every label stays readable at large font sizes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentApprovalChoices(
    choices: List<AgentApprovalChoice>,
    actions: AgentItemActions,
) {
    val primary = choices.firstOrNull { it.allows }
    val send = { choice: AgentApprovalChoice ->
        actions.onSend(AgentRequest.Choice(choiceId = choice.id, words = choice.label))
    }
    if (choices.size <= 2 && choices.all { it.label.length <= SIDE_BY_SIDE_LABEL_LENGTH }) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (choices.filterNot { it == primary } + listOfNotNull(primary)).forEach { choice ->
                AgentChoiceButton(choice.label, choice == primary, actions.canSend) { send(choice) }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val others = choices.filter { it.allows && it != primary } + choices.filterNot { it.allows }
            (listOfNotNull(primary) + others).forEach { choice ->
                AgentChoiceButton(choice.label, choice == primary, actions.canSend, Modifier.fillMaxWidth()) {
                    send(choice)
                }
            }
        }
    }
}

/**
 * The primary choice is a button; every other choice is a text button, so one action leads even on
 * AMOLED where filled buttons are drawn as outlines. All keep the 48 dp touch target.
 */
@Composable
internal fun AgentChoiceButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val sized = modifier.heightIn(min = AgentButtonHeight)
    val text: @Composable () -> Unit = {
        // A choice is read in full before it is sent: its scope can sit at the end of a long label.
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = sized,
            colors = outlineButtonColors(),
            border = amoledOutlineBorder(enabled),
            contentPadding = AgentButtonPadding,
        ) { text() }
    } else {
        TextButton(onClick = onClick, enabled = enabled, modifier = sized, contentPadding = AgentButtonPadding) {
            text()
        }
    }
}

/** Shows local admission and the wait for the agent to confirm. */
@Composable
internal fun AgentRequestStatusLabel(actions: AgentItemActions) {
    val text =
        when (actions.status) {
            AgentRequestStatus.Idle -> return
            AgentRequestStatus.Sending -> stringResource(R.string.sending)
            AgentRequestStatus.AwaitingAgent -> stringResource(R.string.agent_request_sent, actions.agentName)
        }
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The card for something that waits for a person. Same frame as polls and tool chips; the
 * stronger outline is the one cue that the chat needs the viewer.
 */
@Composable
internal fun AgentWaitingCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

private const val APPROVAL_SUBJECT_MAX_LINES = 8
private const val SIDE_BY_SIDE_LABEL_LENGTH = 14
private val AgentButtonHeight = 40.dp
private val AgentButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
