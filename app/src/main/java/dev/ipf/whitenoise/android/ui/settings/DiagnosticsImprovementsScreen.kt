package dev.ipf.whitenoise.android.ui.settings

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AUDIT_LOG_JSONL_MIME_TYPE
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.auditLogExportMimeType
import dev.ipf.whitenoise.android.state.auditLogShareChooserIntent
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.launch
import java.io.File

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
    val scope = rememberCoroutineScope()
    val auditDiagnostics = rememberAuditDiagnosticsSettings(appState)
    val clearLogsDescription =
        listOf(
            stringResource(R.string.delete_audit_logs),
            stringResource(R.string.diagnostics_storage_device),
            stringResource(R.string.delete_audit_logs_subtitle),
        ).joinToString(". ")
    val state = appState.diagnostics
    var auditLogsBusy by remember { mutableStateOf(false) }
    var confirmAuditUpload by remember { mutableStateOf(false) }
    var exportConfirmOpen by rememberSaveable { mutableStateOf(false) }
    var auditPickerOpen by rememberSaveable { mutableStateOf(false) }
    var deleteConfirmOpen by rememberSaveable { mutableStateOf(false) }
    // The archive staged by the acknowledged export, held until the reader picks a destination.
    // Saved as a path rather than a File so it survives the Activity recreation an open document
    // picker can cause; without it the picker's result would arrive with nothing to write.
    var stagedArchivePath by rememberSaveable { mutableStateOf<String?>(null) }
    // One export at a time, from the acknowledgement through to the chosen destination. Each
    // preparation clears the shared staging directory, so two overlapping exports would delete
    // each other's archive.
    var exportInFlight by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(appState.runtimeGeneration) { appState.refreshSecurityPrivacySettings() }

    /** Runs an audit-log mutation with the busy flag. */
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

    if (confirmAuditUpload) {
        AuditUploadConsentDialog(
            onDismiss = { confirmAuditUpload = false },
            onConfirm = {
                confirmAuditUpload = false
                runAuditMutation {
                    appState.setAuditLogsEnabled(true)
                    auditDiagnostics.refreshDelivery()
                }
            },
        )
    }
    SettingsScaffold(title = stringResource(R.string.diagnostics_improvements), onBack = onBack) {
        SettingsList {
            item(key = "search.DiagnosticsImprovements.1") {
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
                            title = stringResource(R.string.audit_upload_title),
                            checked = appState.auditLogSettings?.enabled == true,
                            onCheckedChange = { enabled ->
                                // Enabling uploads needs the prominent disclosure first; turning them off does not.
                                if (enabled) {
                                    confirmAuditUpload = true
                                } else {
                                    runAuditMutation {
                                        appState.setAuditLogsEnabled(false)
                                        auditDiagnostics.refreshDelivery()
                                    }
                                }
                            },
                            subtitle = stringResource(R.string.audit_upload_subtitle),
                            enabled = appState.auditLogSettings != null && !auditLogsBusy,
                            busy = auditLogsBusy,
                        )
                    }
                }
            }
            item(key = "search.DiagnosticsImprovements.2") { DiagnosticsDisclosure(appState) }
            item(key = "search.DiagnosticsImprovements.delivery") {
                AuditDeliverySection(
                    settings = auditDiagnostics,
                    consentBusy = auditLogsBusy,
                    onRefresh = { scope.launch { auditDiagnostics.refreshDelivery() } },
                    onResume = { scope.launch { auditDiagnostics.resumeDelivery() } },
                )
            }
            item(key = "search.DiagnosticsImprovements.3") {
                SettingsSection(stringResource(R.string.stored_audit_logs))
            }
            item(key = "search.DiagnosticsImprovements.4") {
                SettingsGroup(modifier = Modifier.testTag("diagnostics.stored.group")) {
                    row("audit_retention") { rowContext ->
                        AuditRetentionRow(
                            context = rowContext,
                            settings = auditDiagnostics,
                            onRefresh = { scope.launch { auditDiagnostics.refreshRetention() } },
                            onChoose = { days, confirmed ->
                                scope.launch { auditDiagnostics.chooseRetention(days, confirmed) }
                            },
                        )
                    }
                    row("select_audit_files") { rowContext ->
                        SettingsAction(
                            context = rowContext,
                            title = stringResource(R.string.choose_audit_files),
                            modifier = Modifier.testTag("audit.files.open"),
                            subtitle = stringResource(R.string.choose_audit_files_subtitle),
                            onClick = { auditPickerOpen = true },
                            enabled = !auditLogsBusy && !exportInFlight,
                        )
                    }
                    row("export") { rowContext ->
                        SettingsAction(
                            context = rowContext,
                            title = stringResource(R.string.export_audit_logs),
                            onClick = { if (!exportInFlight) exportConfirmOpen = true },
                            subtitle = stringResource(R.string.export_audit_logs_subtitle),
                            enabled = !auditLogsBusy && !exportInFlight,
                        )
                    }
                    row("delete") { rowContext ->
                        SettingsAction(
                            context = rowContext,
                            title = stringResource(R.string.delete_audit_logs),
                            modifier = Modifier.semantics { contentDescription = clearLogsDescription },
                            onClick = { deleteConfirmOpen = true },
                            subtitle = stringResource(R.string.diagnostics_storage_device),
                            enabled = !auditLogsBusy && !exportInFlight,
                            destructive = true,
                        )
                    }
                }
            }
            item(key = "search.DiagnosticsImprovements.5") {
                Text(
                    stringResource(R.string.delete_audit_logs_subtitle),
                    modifier =
                        Modifier
                            .padding(horizontal = WhiteNoiseSpacing.SettingsSectionInset)
                            .semantics { hideFromAccessibility() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (auditPickerOpen) {
        SelectAuditLogExportDialog(
            appState = appState,
            onDismiss = { auditPickerOpen = false },
            onStaged = { export ->
                auditPickerOpen = false
                stagedArchivePath = export.absolutePath
                exportInFlight = true
            },
        )
    }
    AuditLogExportFlow(
        appState = appState,
        confirmOpen = exportConfirmOpen,
        onConfirmOpenChange = { exportConfirmOpen = it },
        inFlight = exportInFlight,
        onInFlightChange = { exportInFlight = it },
        stagedArchivePath = stagedArchivePath,
        onStagedArchivePathChange = { stagedArchivePath = it },
        runAuditMutation = ::runAuditMutation,
    )
    if (deleteConfirmOpen) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = { Text(stringResource(R.string.diagnostics_clear_confirm_title)) },
            text = { Text(stringResource(R.string.diagnostics_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmOpen = false
                        runAuditMutation { appState.deleteAuditLogs() }
                    },
                ) {
                    Text(
                        stringResource(R.string.diagnostics_clear_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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

/**
 * The acknowledged export, from staging one archive through to the destination the reader picks.
 *
 * State is hoisted so the export row can disable itself while an export owns the flow: each
 * preparation clears the shared staging directory, so two overlapping exports would delete each
 * other's archive.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun AuditLogExportFlow(
    appState: WhiteNoiseAppState,
    confirmOpen: Boolean,
    onConfirmOpenChange: (Boolean) -> Unit,
    inFlight: Boolean,
    onInFlightChange: (Boolean) -> Unit,
    stagedArchivePath: String?,
    onStagedArchivePathChange: (String?) -> Unit,
    runAuditMutation: (suspend () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.export_audit_logs)
    val createDocument = remember { CreateAuditExportDocument() }
    val saveLauncher =
        rememberLauncherForActivityResult(createDocument) { uri ->
            // The picker ran outside this composition; the archive it writes is whichever one this
            // export staged, read back from the retained path rather than from live state a
            // recreation or a later export could have moved on.
            val archive = stagedArchivePath?.let { File(it) }
            onStagedArchivePathChange(null)
            when {
                uri == null || archive == null -> onInFlightChange(false)
                else ->
                    runAuditMutation {
                        try {
                            appState.saveAuditLogArchiveToDocument(context.contentResolver, archive, uri)
                        } finally {
                            onInFlightChange(false)
                        }
                    }
            }
        }
    if (confirmOpen) {
        AuditLogExportConsentDialog(
            onDismiss = { onConfirmOpenChange(false) },
            onConfirm = {
                if (inFlight) return@AuditLogExportConsentDialog
                onConfirmOpenChange(false)
                onInFlightChange(true)
                runAuditMutation { appState.stageAuditLogArchive(onStagedArchivePathChange, onInFlightChange) }
            },
        )
    }
    stagedArchivePath?.let { path ->
        val file = File(path)
        AuditLogExportDestinationDialog(
            singleFile = auditLogExportMimeType(file) == AUDIT_LOG_JSONL_MIME_TYPE,
            onDismiss = {
                onStagedArchivePathChange(null)
                onInFlightChange(false)
            },
            onSave = {
                runCatching { saveLauncher.launch(file) }
                    .onFailure {
                        onStagedArchivePathChange(null)
                        onInFlightChange(false)
                        appState.present(R.string.toast_couldnt_save_audit_logs)
                    }
            },
            onShare = {
                onStagedArchivePathChange(null)
                onInFlightChange(false)
                runCatching {
                    context.startActivity(auditLogShareChooserIntent(context, file, chooserTitle))
                }.onFailure { appState.present(R.string.toast_couldnt_export_audit_logs) }
            },
        )
    }
}

/** Uses the staged file's type and basename for the system document picker. */
private class CreateAuditExportDocument : ActivityResultContract<File, Uri?>() {
    /** Suggests the actual file name and MIME type to the document provider. */
    override fun createIntent(
        context: Context,
        input: File,
    ): Intent = ActivityResultContracts.CreateDocument(auditLogExportMimeType(input)).createIntent(context, input.name)

    /** Returns the destination only when the user completed the system picker. */
    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/**
 * Stages one archive for the acknowledged export, releasing the export guard unless a destination
 * was established.
 *
 * Preparation rethrows cancellation, so the guard is released in a `finally`: leaving it set would
 * disable the export row for the life of the retained screen state with no way for the reader to
 * retry.
 */
private suspend fun WhiteNoiseAppState.stageAuditLogArchive(
    onStagedArchivePathChange: (String?) -> Unit,
    onInFlightChange: (Boolean) -> Unit,
) {
    var staged = false
    try {
        val archive = prepareAuditLogArchiveForExport()
        onStagedArchivePathChange(archive?.absolutePath)
        staged = archive != null
    } finally {
        if (!staged) onInFlightChange(false)
    }
}

/** Writes the staged archive to the picked document, reporting the outcome to the reader. */
private suspend fun WhiteNoiseAppState.saveAuditLogArchiveToDocument(
    resolver: ContentResolver,
    archive: File,
    uri: Uri,
) {
    runCatchingCancellable {
        saveAuditLogArchive(
            archive = archive,
            openOutput = { resolver.openOutputStream(uri, "wt") },
            discardOutput = { discardAuditLogArchiveDocument(resolver, uri) },
        )
        // Success is transient: it auto-dismisses after a couple of seconds, where a failure stays
        // until the reader acknowledges it.
    }.onSuccess { presentTransient(R.string.toast_audit_logs_saved) }
        .onFailure { present(R.string.toast_couldnt_save_audit_logs) }
}

/** Where the staged archive goes: this device, or a recipient through the normal chooser. */
@Suppress("FunctionNaming")
@Composable
internal fun AuditLogExportDestinationDialog(
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    singleFile: Boolean = false,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (singleFile) R.string.export else R.string.export_audit_logs_destination_title))
        },
        text = {
            Text(
                stringResource(
                    if (singleFile) {
                        R.string.export_audit_logs_confirm_body
                    } else {
                        R.string.export_audit_logs_destination_body
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(R.string.export_audit_logs_save)) }
        },
        dismissButton = {
            TextButton(onClick = onShare) { Text(stringResource(R.string.export_audit_logs_share)) }
        },
    )
}

/** Export requires an explicit acknowledgement that technical diagnostic data is sensitive. */
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
