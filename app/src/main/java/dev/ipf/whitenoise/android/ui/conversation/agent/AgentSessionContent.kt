@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentSessionSetting
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
                Text(stringResource(if (expanded) R.string.group_system_hide_details else R.string.agent_session_controls))
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
        session.settings.forEach { setting -> AgentSessionSettingMenu(setting, actions) }
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
            AgentSessionHelp(listOf("/${command.name}", command.hint, command.description).filterNotNull().joinToString(" "))
        }
    }
}

@Composable
private fun AgentSessionSettingMenu(
    setting: AgentSessionSetting,
    actions: AgentItemActions,
) {
    var expanded by rememberSaveable(setting.id) { mutableStateOf(false) }
    val label = listOfNotNull(setting.label, setting.value).joinToString(" · ")
    if (setting.options.isEmpty()) {
        AgentSessionSettingInput(setting, label, actions)
        return
    }
    Box {
        TextButton(enabled = actions.canSend, onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded && actions.canSend, onDismissRequest = { expanded = false }) {
            setting.options.forEach { value ->
                DropdownMenuItem(
                    text = { Text(value) },
                    enabled = value != setting.value,
                    onClick = {
                        expanded = false
                        actions.onSend(AgentRequest.Set(setting.id, value, "${setting.label} · $value"))
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentSessionSettingInput(
    setting: AgentSessionSetting,
    label: String,
    actions: AgentItemActions,
) {
    var expanded by rememberSaveable(setting.id) { mutableStateOf(false) }
    TextButton(enabled = actions.canSend, onClick = { expanded = !expanded }) { Text(label) }
    if (expanded) {
        val input = remember(setting.id, setting.value) { TextFieldState(initialText = setting.value.orEmpty()) }
        val value = input.text.toString().trim()
        WhiteNoiseTextField(
            state = input,
            enabled = actions.canSend,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(setting.label) },
        )
        TextButton(
            enabled = actions.canSend && value.isNotEmpty() && value != setting.value,
            onClick = {
                expanded = false
                actions.onSend(AgentRequest.Set(setting.id, value, "${setting.label} · $value"))
            },
        ) {
            Text(stringResource(R.string.agent_session_apply))
        }
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
