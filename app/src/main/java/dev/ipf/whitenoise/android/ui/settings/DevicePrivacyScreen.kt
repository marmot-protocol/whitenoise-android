package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Screenshot
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
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppLockDelay
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.GroupSwitchRow
import dev.ipf.whitenoise.android.ui.common.SettingsGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevicePrivacyScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    var telemetryBusy by remember { mutableStateOf(false) }
    var auditLogsBusy by remember { mutableStateOf(false) }
    var deleteAuditLogsConfirmOpen by remember { mutableStateOf(false) }

    fun runAuditMutation(block: suspend () -> Unit) {
        auditLogsBusy = true
        appState.launchMutation {
            try {
                block()
            } finally {
                auditLogsBusy = false
            }
        }
    }

    LaunchedEffect(appState.runtimeGeneration) {
        appState.refreshAppLockCredentialAvailability()
        appState.refreshSecurityPrivacySettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_privacy)) },
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
                SettingsGroup {
                    item {
                        GroupSwitchRow(
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
                            icon = Icons.Filled.Lock,
                            onCheckedChange = { appState.updateRequireAppUnlock(it) },
                        )
                    }
                    if (appState.requireAppUnlock) {
                        item {
                            Column(Modifier.padding(vertical = 8.dp)) {
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
                        }
                    }
                    item {
                        GroupSwitchRow(
                            title = stringResource(R.string.force_incognito_keyboard),
                            subtitle = stringResource(R.string.force_incognito_keyboard_subtitle),
                            checked = appState.forceIncognitoKeyboard,
                            icon = Icons.Filled.Keyboard,
                            onCheckedChange = { appState.updateForceIncognitoKeyboard(it) },
                        )
                    }
                    item {
                        GroupSwitchRow(
                            title = stringResource(R.string.allow_chat_screenshots),
                            subtitle = stringResource(R.string.allow_chat_screenshots_subtitle),
                            checked = !appState.allowChatScreenshotsInChats,
                            icon = Icons.Filled.Screenshot,
                            onCheckedChange = { appState.updateAllowChatScreenshotsInChats(!it) },
                        )
                    }
                    item {
                        GroupSwitchRow(
                            title = stringResource(R.string.telemetry),
                            subtitle = stringResource(R.string.telemetry_settings_subtitle),
                            checked = appState.relayTelemetrySettings?.exportEnabled == true,
                            enabled = !telemetryBusy,
                            busy = telemetryBusy,
                            icon = Icons.Filled.Analytics,
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
                    }
                    item {
                        GroupSwitchRow(
                            title = stringResource(R.string.audit_logs),
                            subtitle = stringResource(R.string.audit_logs_settings_subtitle),
                            checked = appState.auditLogSettings?.enabled == true,
                            enabled = !auditLogsBusy,
                            busy = auditLogsBusy,
                            icon = Icons.Filled.Article,
                            onCheckedChange = { enabled ->
                                runAuditMutation { appState.setAuditLogsEnabled(enabled) }
                            },
                        )
                    }
                    if (appState.auditLogSettings?.enabled == true) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                AuditRedactionSwitch(
                                    checked = appState.redactSensitiveAuditData,
                                    enabled = !auditLogsBusy,
                                    busy = auditLogsBusy,
                                    onApplyRedaction = { redact ->
                                        runAuditMutation { appState.setRedactSensitiveAuditData(redact) }
                                    },
                                )
                            }
                        }
                    }
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !auditLogsBusy) { deleteAuditLogsConfirmOpen = true }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
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
                    }
                }
            }
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
                        runAuditMutation { appState.deleteAuditLogs() }
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
