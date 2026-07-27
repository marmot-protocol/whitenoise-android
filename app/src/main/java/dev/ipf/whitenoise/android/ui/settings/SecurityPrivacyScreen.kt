package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppLockDelay
import dev.ipf.whitenoise.android.state.AuditRedactionToggleDecision
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.auditRedactionToggleDecision
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.SectionCard
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SecurityPrivacyScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var telemetryBusy by remember { mutableStateOf(false) }
    var auditLogsBusy by remember { mutableStateOf(false) }

    LaunchedEffect(appState.runtimeGeneration) {
        appState.refreshAppLockCredentialAvailability()
        appState.refreshSecurityPrivacySettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_and_privacy)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.security_and_privacy)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.require_app_unlock),
                        subtitle =
                            stringResource(
                                if (appState.appLockCredentialAvailable) {
                                    R.string.require_app_unlock_subtitle
                                } else {
                                    R.string.app_lock_screen_lock_required_hint
                                },
                            ),
                        checked = appState.requireAppUnlock,
                        enabled = appState.appLockCredentialAvailable,
                        onCheckedChange = { appState.updateRequireAppUnlock(it) },
                    )
                    if (appState.requireAppUnlock) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.app_lock_delay_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Column(Modifier.selectableGroup()) {
                            AppLockDelay.entries.forEach { delay ->
                                SelectableSettingsRow(
                                    title = stringResource(delay.labelRes),
                                    selected = appState.appLockDelay == delay,
                                    onClick = { appState.updateAppLockDelay(delay) },
                                )
                            }
                        }
                    }
                    AppDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow(
                        title = stringResource(R.string.force_incognito_keyboard),
                        subtitle = stringResource(R.string.force_incognito_keyboard_subtitle),
                        checked = appState.forceIncognitoKeyboard,
                        onCheckedChange = { appState.updateForceIncognitoKeyboard(it) },
                    )
                    AppDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow(
                        title = stringResource(R.string.allow_chat_screenshots),
                        subtitle = stringResource(R.string.allow_chat_screenshots_subtitle),
                        checked = !appState.allowChatScreenshotsInChats,
                        onCheckedChange = { appState.updateAllowChatScreenshotsInChats(!it) },
                    )
                    AppDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow(
                        title = stringResource(R.string.telemetry),
                        subtitle = stringResource(R.string.telemetry_settings_subtitle),
                        checked = appState.relayTelemetrySettings?.exportEnabled == true,
                        enabled = !telemetryBusy,
                        busy = telemetryBusy,
                        onCheckedChange = { enabled ->
                            telemetryBusy = true
                            appState.launchMutation {
                                try {
                                    appState.setTelemetryEnabled(enabled)
                                } finally {
                                    telemetryBusy = false
                                }
                            }
                        },
                    )
                    AppDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow(
                        title = stringResource(R.string.audit_logs),
                        subtitle = stringResource(R.string.audit_logs_settings_subtitle),
                        checked = appState.auditLogSettings?.enabled == true,
                        enabled = !auditLogsBusy,
                        busy = auditLogsBusy,
                        onCheckedChange = { enabled ->
                            auditLogsBusy = true
                            appState.launchMutation {
                                try {
                                    appState.setAuditLogsEnabled(enabled)
                                } finally {
                                    auditLogsBusy = false
                                }
                            }
                        },
                    )
                    if (appState.auditLogSettings?.enabled == true) {
                        var fullAuditDataConfirmOpen by remember { mutableStateOf(false) }
                        AppDivider(Modifier.padding(vertical = 12.dp))
                        SettingsSwitchRow(
                            title = stringResource(R.string.redact_audit_data),
                            subtitle = stringResource(R.string.redact_audit_data_subtitle),
                            checked = appState.redactSensitiveAuditData,
                            enabled = !auditLogsBusy,
                            busy = auditLogsBusy,
                            onCheckedChange = { redact ->
                                when (val decision = auditRedactionToggleDecision(redact)) {
                                    AuditRedactionToggleDecision.RequireFullDataConfirmation -> {
                                        fullAuditDataConfirmOpen = true
                                    }
                                    is AuditRedactionToggleDecision.ApplyImmediately -> {
                                        auditLogsBusy = true
                                        appState.launchMutation {
                                            try {
                                                appState.setRedactSensitiveAuditData(decision.redact)
                                            } finally {
                                                auditLogsBusy = false
                                            }
                                        }
                                    }
                                }
                            },
                        )
                        if (fullAuditDataConfirmOpen) {
                            AlertDialog(
                                onDismissRequest = { fullAuditDataConfirmOpen = false },
                                title = { Text(stringResource(R.string.redact_audit_data_confirm_title)) },
                                text = { Text(stringResource(R.string.redact_audit_data_confirm_body)) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            fullAuditDataConfirmOpen = false
                                            auditLogsBusy = true
                                            appState.launchMutation {
                                                try {
                                                    appState.setRedactSensitiveAuditData(false)
                                                } finally {
                                                    auditLogsBusy = false
                                                }
                                            }
                                        },
                                    ) {
                                        Text(
                                            stringResource(R.string.redact_audit_data_confirm_action),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { fullAuditDataConfirmOpen = false }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                },
                            )
                        }
                    }
                    AppDivider(Modifier.padding(vertical = 12.dp))
                    var deleteAuditLogsConfirmOpen by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !auditLogsBusy) { deleteAuditLogsConfirmOpen = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.delete_audit_logs),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                stringResource(R.string.delete_audit_logs_subtitle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (deleteAuditLogsConfirmOpen) {
                        AlertDialog(
                            onDismissRequest = { deleteAuditLogsConfirmOpen = false },
                            title = { Text(stringResource(R.string.delete_audit_logs)) },
                            text = { Text(stringResource(R.string.delete_audit_logs_subtitle)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        deleteAuditLogsConfirmOpen = false
                                        auditLogsBusy = true
                                        appState.launchMutation {
                                            try {
                                                appState.deleteAuditLogs()
                                            } finally {
                                                auditLogsBusy = false
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { deleteAuditLogsConfirmOpen = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            },
                        )
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.developer)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.developer_mode),
                        subtitle = stringResource(R.string.developer_mode_subtitle),
                        checked = appState.developerMode,
                        onCheckedChange = { appState.updateDeveloperMode(it) },
                    )
                    if (appState.developerMode) {
                        AppDivider(Modifier.padding(vertical = 12.dp))
                        SettingsRow(stringResource(R.string.diagnostics), stringResource(R.string.diagnostics_settings_subtitle)) { onOpenDiagnostics() }
                        AppDivider(Modifier.padding(vertical = 12.dp))
                        SettingsSwitchRow(
                            title = stringResource(R.string.streaming_debug),
                            subtitle = stringResource(R.string.streaming_debug_subtitle),
                            checked = appState.streamingDebugMode,
                            onCheckedChange = { appState.updateStreamingDebugMode(it) },
                        )
                    }
                }
            }
        }
    }
}
