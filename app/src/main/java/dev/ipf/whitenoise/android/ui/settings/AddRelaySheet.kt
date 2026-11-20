package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RelayAddressAttention
import dev.ipf.whitenoise.android.state.relayAddressAttention
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.rememberSheetTerminalMotion
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** The Add relay task sheet: a secure URL, the lists it should join, and one pinned Add action. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun AddRelaySheet(
    existing: List<AccountRelay>,
    busy: Boolean,
    rejectedUrl: String?,
    onDismiss: () -> Unit,
    onAdd: (String, Set<AccountRelayRole>, () -> Unit) -> Unit,
    ownerKey: Any? = Unit,
) {
    val value: TextFieldState = rememberTextFieldState()
    var roles by rememberSaveable { mutableStateOf(AccountRelayRole.entries.toSet()) }
    val currentValue = value.text.toString().trim()
    val attention = relayAddressAttention(currentValue)
    val onion = attention == RelayAddressAttention.OnionRequiresTor
    // Mirrors MarmotKit, which refuses onion endpoints at classify/publish: None is a structurally acceptable
    // secure address that is not an onion relay, so an onion address is never offered for publication.
    val acceptable = currentValue.isNotEmpty() && attention == RelayAddressAttention.None
    val duplicate = acceptable && roles.isNotEmpty() && missingRelayRoles(existing, currentValue, roles).isEmpty()
    val rejected = rejectedUrl != null && rejectedUrl == currentValue
    val canAdd = acceptable && !duplicate && roles.isNotEmpty() && !busy
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val terminal = rememberSheetTerminalMotion(sheet, ownerKey)
    val close = { terminal.close(onDismiss) }
    WhiteNoiseModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheet,
        interactionEnabled = terminal.acceptsInput,
    ) {
        Column(Modifier.fillMaxWidth()) {
            WhiteNoiseSheetHeader(title = stringResource(R.string.add_relay), onClose = close)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                WhiteNoiseTextField(
                    state = value,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                            .testTag("relay.add.url"),
                    label = { Text(stringResource(R.string.relay_url)) },
                    placeholder = { Text("wss://relay.example.com") },
                    // An onion address needs visible guidance, not only the error announcement.
                    supportingText = { Text(stringResource(addRelayHelpRes(onion))) },
                    errorMessage = addRelayErrorRes(onion, duplicate || rejected)?.let { stringResource(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
                SettingsSection(stringResource(R.string.use_for))
                SettingsGroup(modifier = Modifier.testTag("relay.add.roles")) {
                    AccountRelayRole.entries.forEach { role ->
                        row(role.name) { context ->
                            SettingsSwitch(
                                context = context,
                                title = stringResource(role.labelRes),
                                checked = role in roles,
                                onCheckedChange = { selected -> roles = if (selected) roles + role else roles - role },
                                subtitle = stringResource(role.helpRes),
                                explanation = stringResource(role.explanationRes()),
                                leading = { SettingsLeadingIcon(role.iconRes) },
                            )
                        }
                    }
                }
                if (roles.isEmpty()) SettingsExplainer(stringResource(R.string.choose_at_least_one_role))
            }
            SettingsBottomAction(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                WhiteNoiseButton(
                    onClick = { onAdd(currentValue, roles) { terminal.close(onDismiss) } },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("relay.add.submit")
                            .explainOnLongPress(
                                Explanation(
                                    stringResource(R.string.add_relay),
                                    stringResource(R.string.explanation_relays_add_submit),
                                ),
                            ),
                    enabled = canAdd && terminal.acceptsInput,
                    loading = busy,
                    loadingLabel = stringResource(R.string.relay_list_publishing),
                ) { Text(stringResource(R.string.add_relay)) }
            }
        }
    }
}
