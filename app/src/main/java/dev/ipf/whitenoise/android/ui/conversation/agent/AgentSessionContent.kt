@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentSessionSetting
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseChoiceRow
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

@Composable
internal fun AgentSessionContent(
    item: AgentItem,
    actions: AgentItemActions,
    modifier: Modifier = Modifier,
) {
    val session = item.body as? AgentItemBody.Session ?: return
    var expanded by rememberSaveable(item.rowId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        AgentSessionDivider(item, actions.agentName)
        if (actions.sessionControls) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                val label = if (expanded) R.string.group_system_hide_details else R.string.agent_session_controls
                Text(stringResource(label))
            }
            if (expanded) {
                AgentSessionControls(session, actions, Modifier.padding(horizontal = WhiteNoiseSpacing.Related))
            }
        }
    }
}

@Composable
private fun AgentSessionControls(
    session: AgentItemBody.Session,
    actions: AgentItemActions,
    modifier: Modifier,
) {
    val newSession = stringResource(R.string.agent_session_new)
    Column(modifier.fillMaxWidth()) {
        AgentRequestStatusLabel(actions)
        TextButton(enabled = actions.canSend, onClick = { actions.onSend(AgentRequest.NewSession(newSession)) }) {
            Text(newSession)
        }
        session.settings.forEach { setting -> AgentSessionSettingControl(setting, actions) }
        session.actions.forEach { action ->
            TextButton(
                enabled = actions.canSend,
                onClick = { actions.onSend(AgentRequest.Own(action.id, action.label)) },
            ) {
                Text(action.label)
            }
            action.description?.let { AgentSessionHelp(it) }
        }
        session.commands.forEach { command ->
            val help = listOfNotNull("/${command.name}", command.hint, command.description).joinToString(" ")
            AgentSessionHelp(help)
        }
    }
}

@Composable
private fun AgentSessionSettingControl(
    setting: AgentSessionSetting,
    actions: AgentItemActions,
) {
    var expanded by rememberSaveable(setting.id) { mutableStateOf(false) }
    val label = listOfNotNull(setting.label, setting.value).joinToString(" · ")
    val disclosureState =
        stringResource(if (expanded) R.string.onboarding_details_expanded else R.string.onboarding_details_collapsed)
    TextButton(
        enabled = actions.canSend,
        onClick = { expanded = !expanded },
        modifier = Modifier.semantics { stateDescription = disclosureState },
    ) {
        Text(label)
    }
    if (!expanded) return
    val apply: (String) -> Unit = { value ->
        expanded = false
        actions.onSend(AgentRequest.Set(setting.id, value, "${setting.label} · $value"))
    }
    if (setting.options.isEmpty()) {
        AgentSessionSettingInput(setting, actions.canSend, apply)
    } else {
        Column(Modifier.selectableGroup()) {
            setting.options.forEach { value ->
                WhiteNoiseChoiceRow(
                    title = value,
                    selected = value == setting.value,
                    onClick = { apply(value) },
                    enabled = actions.canSend && value != setting.value,
                )
            }
        }
    }
}

@Composable
private fun AgentSessionSettingInput(
    setting: AgentSessionSetting,
    enabled: Boolean,
    onApply: (String) -> Unit,
) {
    val input = remember(setting.id, setting.value) { TextFieldState(initialText = setting.value.orEmpty()) }
    val value = input.text.toString().trim()
    WhiteNoiseTextField(
        state = input,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(setting.label) },
    )
    TextButton(
        enabled = enabled && value.isNotEmpty() && value != setting.value,
        onClick = { onApply(value) },
    ) {
        Text(stringResource(R.string.agent_session_apply))
    }
}

@Composable
private fun AgentSessionHelp(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = WhiteNoiseSpacing.Related, vertical = WhiteNoiseSpacing.Related),
    )
}
