package dev.ipf.darkmatter.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.state.DarkMatterAppState
import dev.ipf.darkmatter.updates.AppSelfUpdateState

@Composable
fun AppSelfUpdateDialog(appState: DarkMatterAppState) {
    val context = LocalContext.current
    when (val state = appState.appSelfUpdateState) {
        AppSelfUpdateState.Idle -> Unit
        AppSelfUpdateState.Resolving ->
            AppSelfUpdateProgressDialog(
                title = stringResource(R.string.app_self_update_resolving),
                body = stringResource(R.string.app_self_update_resolving_body),
                showProgress = true,
                indeterminate = true,
                onCancel = { appState.cancelAppSelfUpdate() },
            )
        is AppSelfUpdateState.Confirming ->
            AlertDialog(
                onDismissRequest = { appState.cancelAppSelfUpdate() },
                title = { Text(stringResource(R.string.app_self_update_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.app_self_update_confirm_message,
                            state.asset.version,
                            formatApkSize(context, state.asset.sizeBytes),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { appState.confirmAppSelfUpdateDownload() }) {
                        Text(stringResource(R.string.app_self_update_download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { appState.cancelAppSelfUpdate() }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        is AppSelfUpdateState.Downloading -> {
            val total = state.totalBytes
            val progress =
                if (total != null && total > 0L) {
                    (state.bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
            AppSelfUpdateProgressDialog(
                title = stringResource(R.string.app_self_update_downloading),
                body =
                    if (total != null && total > 0L) {
                        stringResource(
                            R.string.app_self_update_download_progress,
                            formatApkSize(context, state.bytesRead),
                            formatApkSize(context, total),
                        )
                    } else {
                        stringResource(
                            R.string.app_self_update_download_progress_unknown_total,
                            formatApkSize(context, state.bytesRead),
                        )
                    },
                showProgress = true,
                indeterminate = progress == null,
                progress = progress,
                onCancel = { appState.cancelAppSelfUpdate() },
            )
        }
        is AppSelfUpdateState.Verified ->
            AlertDialog(
                onDismissRequest = { appState.cancelAppSelfUpdate() },
                title = { Text(stringResource(R.string.app_self_update_ready_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.app_self_update_ready_message,
                            state.asset.version,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { appState.launchVerifiedAppSelfUpdate(context) }) {
                        Text(stringResource(R.string.app_self_update_install))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { appState.cancelAppSelfUpdate() }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        is AppSelfUpdateState.PermissionRequired ->
            AlertDialog(
                onDismissRequest = { appState.cancelAppSelfUpdate() },
                title = { Text(stringResource(R.string.app_self_update_permission_title)) },
                text = { Text(stringResource(R.string.app_self_update_permission_message)) },
                confirmButton = {
                    TextButton(onClick = { appState.openAppSelfUpdateInstallPermissionSettings(context) }) {
                        Text(stringResource(R.string.app_self_update_open_settings))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { appState.cancelAppSelfUpdate() }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        is AppSelfUpdateState.Error ->
            AlertDialog(
                onDismissRequest = { appState.cancelAppSelfUpdate() },
                title = { Text(stringResource(R.string.app_self_update_error_title)) },
                text = { Text(stringResource(state.messageRes)) },
                confirmButton = {
                    if (state.retryable) {
                        TextButton(onClick = { appState.retryAppSelfUpdate() }) {
                            Text(stringResource(R.string.app_self_update_retry))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { appState.cancelAppSelfUpdate() }) {
                        Text(stringResource(if (state.retryable) R.string.cancel else R.string.close))
                    }
                },
            )
    }
}

@Composable
private fun AppSelfUpdateProgressDialog(
    title: String,
    body: String,
    showProgress: Boolean,
    indeterminate: Boolean,
    progress: Float? = null,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (showProgress) {
                    if (indeterminate || progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.app_self_update_cancel))
            }
        },
    )
}

private fun formatApkSize(
    context: android.content.Context,
    bytes: Long?,
): String =
    if (bytes == null || bytes <= 0L) {
        context.getString(R.string.app_self_update_size_unknown)
    } else {
        Formatter.formatShortFileSize(context, bytes)
    }
