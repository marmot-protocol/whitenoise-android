@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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

/** Keeps the explicit receipt choice in a bottom sheet with scrollable details and a pinned action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageDiagnosticsPrompt(
    appState: WhiteNoiseAppState,
    onDone: () -> Unit,
) {
    val state = appState.diagnostics
    var loggingBusy by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = {},
        containerColor = amoledSheetContainerColor(),
        dragHandle = null,
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden },
            ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                stringResource(R.string.usage_diagnostics_prompt_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    UsageDiagnosticsFeedback(appState)
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !state.busy && !state.failed && state.snapshot != null && !loggingBusy,
                onClick = {
                    appState.launchMutation {
                        if (state.busy || state.failed) return@launchMutation
                        if (state.snapshot == null) return@launchMutation
                        if (!state.requiresChoice || appState.setTelemetryEnabled(false)) onDone()
                    }
                },
            ) { Text(stringResource(R.string.usage_diagnostics_done)) }
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
    ConsentSwitchRow(
        title = stringResource(R.string.audit_logs),
        subtitle = stringResource(R.string.usage_diagnostics_logs_separate),
        checked = pendingChoice ?: (appState.auditLogSettings?.enabled == true),
        enabled = appState.auditLogSettings != null,
        saving = busy,
        onCheckedChange = { enabled ->
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
        },
    )
    if (failed) {
        Text(stringResource(R.string.usage_diagnostics_error), color = MaterialTheme.colorScheme.error)
    }
}
