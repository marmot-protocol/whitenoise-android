package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.auditLogShareChooserIntent
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * Diagnostics & Improvements: the native usage receipt and the independent audit-log choice as two switches, the
 * disclosure and exporter status beneath them, and the stored audit files' export and delete actions (D14, M098).
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun DiagnosticsImprovementsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state = appState.diagnostics
    var auditLogsBusy by remember { mutableStateOf(false) }
    var exportConfirmOpen by rememberSaveable { mutableStateOf(false) }
    var deleteConfirmOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(appState.runtimeGeneration) { appState.refreshSecurityPrivacySettings() }

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

    SettingsScaffold(title = stringResource(R.string.diagnostics_improvements), onBack = onBack) {
        SettingsList {
            item {
                SettingsGroup(modifier = Modifier.testTag("diagnostics.choices.group")) {
                    row("usage") { rowContext ->
                        SettingsSwitch(
                            context = rowContext,
                            title = stringResource(R.string.usage_diagnostics_title),
                            checked = state.selected,
                            onCheckedChange = { enabled ->
                                appState.launchMutation { appState.setTelemetryEnabled(enabled) }
                            },
                            subtitle = stringResource(R.string.usage_diagnostics_subtitle),
                            enabled = state.snapshot != null && !state.failed,
                            busy = state.busy,
                        )
                    }
                    row("audit_logs") { rowContext ->
                        SettingsSwitch(
                            context = rowContext,
                            title = stringResource(R.string.audit_logs),
                            checked = appState.auditLogSettings?.enabled == true,
                            onCheckedChange = { enabled -> runAuditMutation { appState.setAuditLogsEnabled(enabled) } },
                            subtitle = stringResource(R.string.audit_logs_settings_subtitle),
                            enabled = appState.auditLogSettings != null && !auditLogsBusy,
                            busy = auditLogsBusy,
                        )
                    }
                }
            }
            item { DiagnosticsDisclosure(appState) }
            item { SettingsSection(stringResource(R.string.stored_audit_logs)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("diagnostics.stored.group")) {
                    row("export") { rowContext ->
                        SettingsAction(
                            context = rowContext,
                            title = stringResource(R.string.export_audit_logs),
                            onClick = { exportConfirmOpen = true },
                            subtitle = stringResource(R.string.export_audit_logs_subtitle),
                            enabled = !auditLogsBusy,
                        )
                    }
                    row("delete") { rowContext ->
                        SettingsAction(
                            context = rowContext,
                            title = stringResource(R.string.delete_audit_logs),
                            onClick = { deleteConfirmOpen = true },
                            subtitle = stringResource(R.string.delete_audit_logs_subtitle),
                            enabled = !auditLogsBusy,
                            destructive = true,
                        )
                    }
                }
            }
        }
    }
    if (exportConfirmOpen) {
        val chooserTitle = stringResource(R.string.export_audit_logs)
        AuditLogExportConsentDialog(
            onDismiss = { exportConfirmOpen = false },
            onConfirm = {
                exportConfirmOpen = false
                runAuditMutation {
                    val files = appState.prepareAuditLogsForSharing()
                    if (files.isNotEmpty()) {
                        runCatching { context.startActivity(auditLogShareChooserIntent(context, files, chooserTitle)) }
                            .onFailure { appState.present(R.string.toast_couldnt_export_audit_logs) }
                    }
                }
            },
        )
    }
    if (deleteConfirmOpen) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = { Text(stringResource(R.string.delete_audit_logs)) },
            text = { Text(stringResource(R.string.delete_audit_logs_subtitle)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmOpen = false
                        runAuditMutation { appState.deleteAuditLogs() }
                    },
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmOpen = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The collection scope, exporter readiness and any read or save failure, in the explainer column. */
@Suppress("FunctionNaming")
@Composable
private fun DiagnosticsDisclosure(appState: WhiteNoiseAppState) {
    val state = appState.diagnostics
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.SettingsSectionInset),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            UsageDiagnosticsDisclosure()
            if (state.requiresChoice && state.snapshot?.settings?.previouslyEnabled == true) {
                Text(stringResource(R.string.usage_diagnostics_renew), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(
                    R.string.usage_diagnostics_status,
                    stringResource(diagnosticsStatusLabel(state.snapshot?.status?.productAnalytics)),
                    stringResource(diagnosticsStatusLabel(state.snapshot?.status?.telemetry)),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.snapshot == null && !state.failed) {
                Text(stringResource(R.string.usage_diagnostics_loading), style = MaterialTheme.typography.bodySmall)
            }
            if (state.failed) {
                Text(stringResource(R.string.usage_diagnostics_error), color = MaterialTheme.colorScheme.error)
                TextButton(
                    enabled = !state.busy,
                    onClick = { appState.launchMutation { appState.retryUsageDiagnostics() } },
                ) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

/** Export waits for an explicit acknowledgement that audit files may hold message content and identities. */
@Suppress("FunctionNaming")
@Composable
internal fun AuditLogExportConsentDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_audit_logs_confirm_title)) },
        text = { Text(stringResource(R.string.export_audit_logs_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.export_audit_logs_confirm_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
