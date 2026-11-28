@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentQuestion
import dev.ipf.whitenoise.android.core.agent.AgentQuestionOption
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.theme.Radii

/**
 * An open question from the agent. Options look and behave like poll options; Send answer stays
 * disabled until every question has an answer, so a half-filled form is never sent.
 */
@Composable
internal fun AgentQuestionCard(
    item: AgentItem,
    actions: AgentItemActions,
    modifier: Modifier = Modifier,
) {
    val question = item.body as? AgentItemBody.Question ?: return
    var selected by remember(item.rowId) { mutableStateOf(mapOf<String, Set<String>>()) }
    // A later version may add questions; each keeps its own field for as long as the row lives.
    val typing = remember(item.rowId) { HashMap<String, TextFieldState>() }
    question.questions.forEach { typing.getOrPut(it.id, ::TextFieldState) }
    val typed = question.questions.associate { it.id to typing.getValue(it.id).text.toString() }
    val answerable = !question.answerViaMessage && question.questions.none { it.secret } && actions.enabled
    AgentWaitingCard(modifier) {
        question.questions.forEach { asked ->
            AgentAskedQuestion(
                asked = asked,
                prominent = question.questions.size == 1,
                answer = AgentAskedAnswer(selected[asked.id].orEmpty(), typing.getValue(asked.id)),
                controls =
                    AgentAskedControls(
                        enabled = answerable && actions.canSend,
                        onSelect = { optionId ->
                            selected = selected + (asked.id to toggled(asked, selected[asked.id], optionId))
                        },
                    ),
            )
        }
        when {
            question.questions.any { it.secret } -> AgentQuestionNote(stringResource(R.string.agent_question_secret))
            question.answerViaMessage -> AgentQuestionNote(stringResource(R.string.agent_question_reply_in_chat))
        }
        AgentRequestStatusLabel(actions)
        if (answerable) {
            val answers = agentQuestionAnswers(question.questions, selected, typed)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                AgentChoiceButton(
                    label = stringResource(R.string.agent_question_send),
                    primary = true,
                    enabled = actions.canSend && answers != null,
                    onClick = {
                        answers?.let {
                            val words = agentAnswerSummary(question.questions, it)
                            actions.onSend(AgentRequest.Questions(it, words = words))
                        }
                    },
                )
            }
        }
    }
}

private class AgentAskedAnswer(
    val options: Set<String>,
    val text: TextFieldState,
)

private class AgentAskedControls(
    val enabled: Boolean,
    val onSelect: (String) -> Unit,
)

private fun toggled(
    asked: AgentQuestion,
    current: Set<String>?,
    optionId: String,
): Set<String> {
    val chosen = current.orEmpty()
    return when {
        !asked.multiple -> setOf(optionId)
        optionId in chosen -> chosen - optionId
        else -> chosen + optionId
    }
}

@Composable
private fun AgentAskedQuestion(
    asked: AgentQuestion,
    prominent: Boolean,
    answer: AgentAskedAnswer,
    controls: AgentAskedControls,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        asked.header?.let { header ->
            Text(
                text = header,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = asked.text,
            style =
                (if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                    .copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (asked.options.isNotEmpty()) {
            Text(
                text =
                    stringResource(
                        if (asked.multiple) {
                            R.string.agent_question_multiple_choice
                        } else {
                            R.string.agent_question_single_choice
                        },
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        asked.options.forEach { option ->
            AgentQuestionOptionRow(option, option.id in answer.options, asked.multiple, controls.enabled) {
                controls.onSelect(option.id)
            }
        }
        if (asked.allowText && !asked.secret) {
            WhiteNoiseTextField(
                state = answer.text,
                enabled = controls.enabled,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.agent_question_text_hint)) },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A pill like a poll option: outlined, with a check and a thicker outline once chosen. */
@Composable
private fun AgentQuestionOptionRow(
    option: AgentQuestionOption,
    selected: Boolean,
    multiple: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.xl)
    val selection =
        if (multiple) {
            Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox) { onClick() }
        } else {
            Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(shape)
                .border(
                    BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    shape,
                ).then(selection)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = (if (selected) "✓  " else "") + option.label,
            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface,
        )
        option.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentQuestionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}
