package dev.ipf.whitenoise.android.ui.settings

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AuditLogFileFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.listAuditLogExportCandidates
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.stageSelectedAuditLogs
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

/** Loads the audit file inventory only while the picker is open. */
@Suppress("FunctionNaming")
@Composable
internal fun SelectAuditLogExportDialog(
    appState: WhiteNoiseAppState,
    onDismiss: () -> Unit,
    onStaged: (File) -> Unit,
) {
    val generation = appState.runtimeGeneration
    val scope = rememberCoroutineScope()
    var result by remember(generation) { mutableStateOf<Result<List<AuditLogFileFfi>>?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    LaunchedEffect(generation, attempt) {
        result = runCatchingCancellable { appState.listAuditLogExportCandidates() }
    }
    AuditLogPickerDialog(
        result = result,
        exporting = exporting,
        onDismiss = onDismiss,
        onExport = { selectedPaths ->
            if (!exporting) {
                exporting = true
                scope.launch {
                    try {
                        val staged = runCatchingCancellable { appState.stageSelectedAuditLogs(selectedPaths) }
                        if (generation == appState.runtimeGeneration) {
                            staged
                                .onSuccess(onStaged)
                                .onFailure { result = Result.failure(it) }
                        }
                    } finally {
                        exporting = false
                    }
                }
            }
        },
        onRetry = {
            result = null
            attempt++
        },
    )
}

/** Selects whole files by date and size without opening their contents in the UI. */
@Suppress("FunctionNaming")
@Composable
internal fun AuditLogPickerDialog(
    result: Result<List<AuditLogFileFfi>>?,
    exporting: Boolean,
    onDismiss: () -> Unit,
    onExport: (Set<String>) -> Unit,
    onRetry: () -> Unit,
) {
    val files = result?.getOrNull().orEmpty()
    var selected by remember(result) { mutableStateOf(emptySet<String>()) }
    WhiteNoiseAlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        title = { Text(stringResource(R.string.choose_audit_files)) },
        text = {
            when {
                result == null -> CircularProgressIndicator()
                result.isFailure -> Text(stringResource(R.string.toast_couldnt_export_audit_logs))
                files.isEmpty() -> Text(stringResource(R.string.toast_no_audit_logs_to_export))
                else ->
                    WhiteNoiseLazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = AuditLogPickerMaxHeight)) {
                        items(files, key = { it.path }) { file ->
                            AuditLogPickerRow(file, file.path in selected, enabled = !exporting) { checked ->
                                selected = if (checked) selected + file.path else selected - file.path
                            }
                        }
                    }
            }
        },
        confirmButton = {
            if (result?.isFailure == true) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            } else {
                TextButton(onClick = { onExport(selected) }, enabled = selected.isNotEmpty() && !exporting) {
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(AuditLogPickerProgressSize),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(WhiteNoiseSpacing.Related))
                    }
                    Text(stringResource(R.string.export))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !exporting) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Presents one selectable file. The checkbox and label share a single accessibility action. */
@Suppress("FunctionNaming")
@Composable
private fun AuditLogPickerRow(
    file: AuditLogFileFfi,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val details =
        remember(context, file.modifiedAtMs, file.sizeBytes) {
            val dateTime =
                file.modifiedAtMs?.let {
                    val date = Date(it.toLong())
                    val day = DateFormat.getMediumDateFormat(context).format(date)
                    val time = DateFormat.getTimeFormat(context).format(date)
                    "$day $time"
                }
            val size = Formatter.formatShortFileSize(context, file.sizeBytes.toLong()).replace(' ', '\u00a0')
            listOfNotNull(dateTime, size).joinToString(" · ")
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AuditLogPickerRowMinHeight)
                .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
                .padding(vertical = WhiteNoiseSpacing.Related),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                file.fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private val AuditLogPickerMaxHeight = 320.dp
private val AuditLogPickerRowMinHeight = 56.dp
private val AuditLogPickerProgressSize = 16.dp
