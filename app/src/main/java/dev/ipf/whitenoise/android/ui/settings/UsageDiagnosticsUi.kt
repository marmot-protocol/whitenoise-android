@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.DiagnosticsExporterStatusFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

/** Shows the actual collection scope wherever a user can grant the expanded MDK receipt. */
@Composable
internal fun UsageDiagnosticsDisclosure() {
    val operator =
        BuildConfig.WHITENOISE_PRODUCT_OPERATOR
            .takeUnless { it.isBlank() || it == "white_noise" } ?: "White Noise"
    val retention =
        BuildConfig.WHITENOISE_PRODUCT_RETENTION
            .ifBlank { stringResource(R.string.usage_diagnostics_retention_unknown) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.usage_diagnostics_disclosure), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(
                R.string.usage_diagnostics_operator,
                operator,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            retention,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Maps MDK's independent exporter decisions to truthful user-facing status. */
internal fun diagnosticsStatusLabel(status: DiagnosticsExporterStatusFfi?): Int =
    when (status) {
        DiagnosticsExporterStatusFfi.DISABLED -> R.string.usage_diagnostics_off
        DiagnosticsExporterStatusFfi.CONSENT_REQUIRED -> R.string.usage_diagnostics_permission_needed
        DiagnosticsExporterStatusFfi.UNCONFIGURED -> R.string.usage_diagnostics_unconfigured
        DiagnosticsExporterStatusFfi.UNSUPPORTED_BUILD -> R.string.usage_diagnostics_unsupported
        DiagnosticsExporterStatusFfi.READY -> R.string.usage_diagnostics_ready
        DiagnosticsExporterStatusFfi.CONFIGURATION_REJECTED -> R.string.usage_diagnostics_rejected
        null -> R.string.usage_diagnostics_unknown
    }

/** Presents the pending choice immediately while the native receipt remains the collection authority. */
@Composable
private fun UsageDiagnosticsChoice(appState: WhiteNoiseAppState) {
    val state = appState.diagnostics
    ConsentSwitchRow(
        title = stringResource(R.string.usage_diagnostics_title),
        subtitle = stringResource(R.string.usage_diagnostics_subtitle),
        checked = state.selected,
        enabled = state.snapshot != null && !state.failed,
        saving = state.busy,
        onCheckedChange = { enabled -> appState.launchMutation { appState.setTelemetryEnabled(enabled) } },
    )
}

/** Keeps loading and failed-save recovery visible without replacing or moving either choice. */
@Composable
private fun UsageDiagnosticsFeedback(appState: WhiteNoiseAppState) {
    val state = appState.diagnostics
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

/**
 * The prototype's consent sheet: a title with a close glyph, the choices, then the
 * disclosure. It carries no confirm button, so closing the sheet — by the glyph, a drag,
 * the scrim or back — is what records the receipt, and the sheet refuses to close while a
 * choice is still being written so no dismissal can lose one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageDiagnosticsPrompt(
    appState: WhiteNoiseAppState,
    onDone: () -> Unit,
) {
    val state = appState.diagnostics
    var loggingBusy by remember { mutableStateOf(false) }
    val settled = !state.busy && !state.failed && state.snapshot != null && !loggingBusy
    val finish = {
        appState.launchMutation {
            if (!state.busy && !state.failed && state.snapshot != null) {
                // Closing without enabling uploads records the declined audit choice, as Done did.
                val auditSettled =
                    !appState.auditUploadConsentRequired || appState.setAuditLogsEnabled(false)
                if (auditSettled && (!state.requiresChoice || appState.setTelemetryEnabled(false))) onDone()
            }
        }
        Unit
    }
    ModalBottomSheet(
        onDismissRequest = { if (settled) finish() },
        containerColor = amoledSheetContainerColor(),
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || settled },
            ),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Section)) {
            WhiteNoiseSheetHeader(
                title = stringResource(R.string.usage_diagnostics_prompt_title),
                onClose = finish,
                closeEnabled = settled,
            )
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                    .padding(bottom = WhiteNoiseSpacing.Section)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.ConversationCluster),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column {
                        UsageDiagnosticsChoice(appState)
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        IndependentAuditLogChoice(appState, loggingBusy) { loggingBusy = it }
                    }
                }
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageDiagnosticsDisclosure()
                    if (appState.auditUploadConsentRequired) {
                        Text(stringResource(R.string.audit_upload_renew), style = MaterialTheme.typography.bodySmall)
                    }
                    UsageDiagnosticsFeedback(appState)
                }
            }
        }
    }
}

/** Saves technical logging independently of the usage receipt and reports failures in place. */
@Composable
private fun IndependentAuditLogChoice(
    appState: WhiteNoiseAppState,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
) {
    var failed by remember { mutableStateOf(false) }
    var pendingChoice by remember { mutableStateOf<Boolean?>(null) }
    var confirmUpload by remember { mutableStateOf(false) }

    fun saveChoice(enabled: Boolean) {
        pendingChoice = enabled
        onBusyChange(true)
        appState.launchMutation {
            try {
                failed = !appState.setAuditLogsEnabled(enabled)
            } finally {
                pendingChoice = null
                onBusyChange(false)
            }
        }
    }
    ConsentSwitchRow(
        title = stringResource(R.string.audit_upload_title),
        subtitle = stringResource(R.string.audit_upload_subtitle),
        checked = pendingChoice ?: (appState.auditLogSettings?.enabled == true),
        enabled = appState.auditLogSettings != null,
        saving = busy,
        onCheckedChange = { enabled ->
            if (enabled) confirmUpload = true else saveChoice(false)
        },
    )
    if (confirmUpload) {
        AuditUploadConsentDialog(
            onDismiss = { confirmUpload = false },
            onConfirm = {
                confirmUpload = false
                saveChoice(true)
            },
        )
    }
    if (failed) {
        Text(stringResource(R.string.usage_diagnostics_error), color = MaterialTheme.colorScheme.error)
    }
}

/** Prominent disclosure shared by both audit opt-in entry points. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuditUploadConsentDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        AuditUploadConsentContent(onDismiss, onConfirm)
    }
}

/** Scrolls the sensitive-data explanation while keeping both decisions reachable. */
@Composable
internal fun AuditUploadConsentContent(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.widthIn(max = 360.dp).heightIn(max = 640.dp).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.audit_upload_confirm_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.audit_upload_disclosure),
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onConfirm) {
                Text(stringResource(R.string.audit_upload_confirm))
            }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
