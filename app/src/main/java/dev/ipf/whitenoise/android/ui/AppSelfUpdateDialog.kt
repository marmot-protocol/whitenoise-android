package dev.ipf.whitenoise.android.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.updates.AppSelfUpdateState
import dev.ipf.whitenoise.android.updates.AppUpdateInfo

internal const val APP_UPDATE_DIALOG_TAG = "appUpdate.dialog"

/** Renders only the distribution-owned real update state; platform actions remain in the existing flow. */
@Composable
fun AppSelfUpdateDialog(appState: WhiteNoiseAppState) {
    val context = LocalContext.current
    val state = appState.appSelfUpdateState
    val info = appState.appUpdateInfo
    val reminderVersion =
        when (state) {
            is AppSelfUpdateState.Confirming -> state.asset.version
            AppSelfUpdateState.Resolving, is AppSelfUpdateState.Error -> info.latestVersion
            else -> null
        }
    val canRemindLater =
        reminderVersion != null &&
            reminderVersion == info.latestVersion &&
            info.isUpdateAvailable &&
            !info.isFarBehind
    AppSelfUpdateContent(
        state = state,
        selfUpdateEnabled = BuildConfig.SELF_UPDATE_ENABLED,
        canRemindLater = canRemindLater,
        onCancel = appState::cancelAppSelfUpdate,
        onDownload = appState::confirmAppSelfUpdateDownload,
        onInstall = { appState.launchVerifiedAppSelfUpdate(context) },
        onOpenSettings = { appState.openAppSelfUpdateInstallPermissionSettings(context) },
        onRetry = appState::retryAppSelfUpdate,
        onRemindLater = {
            remindLaterAppUpdate(
                expectedVersion = reminderVersion,
                state = appState.appSelfUpdateState,
                info = appState.appUpdateInfo,
                selfUpdateEnabled = BuildConfig.SELF_UPDATE_ENABLED,
                onDismissLatest = appState::dismissAppUpdateBanner,
                onCancel = appState::cancelAppSelfUpdate,
            )
        },
    )
}

/** Presentation of authoritative flow phases, with no inferred verification or installation success. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun AppSelfUpdateContent(
    state: AppSelfUpdateState,
    selfUpdateEnabled: Boolean,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    canRemindLater: Boolean = false,
    onRemindLater: () -> Unit = {},
) {
    if (!selfUpdateEnabled) return
    val context = LocalContext.current
    when (state) {
        AppSelfUpdateState.Idle -> Unit
        AppSelfUpdateState.Resolving ->
            AppSelfUpdateProgressDialog(
                title = stringResource(R.string.app_self_update_resolving),
                body = stringResource(R.string.app_self_update_resolving_body),
                showProgress = true,
                indeterminate = true,
                onCancel = onCancel,
                canRemindLater = canRemindLater,
                onRemindLater = onRemindLater,
            )
        is AppSelfUpdateState.Confirming ->
            WhiteNoiseAlertDialog(
                modifier = Modifier.testTag(APP_UPDATE_DIALOG_TAG),
                onDismissRequest = onCancel,
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
                    TextButton(onClick = onDownload, modifier = Modifier.testTag("appUpdate.download")) {
                        Text(stringResource(R.string.app_self_update_download))
                    }
                },
                dismissButton = {
                    AppSelfUpdateCancelActions(onCancel, stringResource(R.string.cancel), canRemindLater, onRemindLater)
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
                onCancel = onCancel,
            )
        }
        is AppSelfUpdateState.Verifying ->
            AppSelfUpdateProgressDialog(
                title = stringResource(R.string.app_self_update_verifying),
                body = stringResource(R.string.app_self_update_verifying_body),
                showProgress = true,
                indeterminate = true,
                onCancel = onCancel,
            )
        is AppSelfUpdateState.Verified ->
            WhiteNoiseAlertDialog(
                modifier = Modifier.testTag(APP_UPDATE_DIALOG_TAG),
                onDismissRequest = onCancel,
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
                    TextButton(onClick = onInstall, modifier = Modifier.testTag("appUpdate.install")) {
                        Text(stringResource(R.string.app_self_update_install))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("appUpdate.cancel")) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        is AppSelfUpdateState.PermissionRequired ->
            WhiteNoiseAlertDialog(
                modifier = Modifier.testTag(APP_UPDATE_DIALOG_TAG),
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.app_self_update_permission_title)) },
                text = { Text(stringResource(R.string.app_self_update_permission_message)) },
                confirmButton = {
                    TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("appUpdate.settings")) {
                        Text(stringResource(R.string.app_self_update_open_settings))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("appUpdate.cancel")) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        is AppSelfUpdateState.Error ->
            WhiteNoiseAlertDialog(
                modifier = Modifier.testTag(APP_UPDATE_DIALOG_TAG),
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.app_self_update_error_title)) },
                text = { Text(stringResource(state.messageRes)) },
                confirmButton = {
                    if (state.retryable) {
                        TextButton(onClick = onRetry, modifier = Modifier.testTag("appUpdate.retry")) {
                            Text(stringResource(R.string.app_self_update_retry))
                        }
                    }
                },
                dismissButton = {
                    AppSelfUpdateCancelActions(
                        onCancel,
                        stringResource(if (state.retryable) R.string.cancel else R.string.close),
                        canRemindLater,
                        onRemindLater,
                    )
                },
            )
    }
}

/** Names the real operation and exposes bounded or indeterminate progress with an explicit cancellation action. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun AppSelfUpdateProgressDialog(
    title: String,
    body: String,
    showProgress: Boolean,
    indeterminate: Boolean,
    progress: Float? = null,
    onCancel: () -> Unit,
    canRemindLater: Boolean = false,
    onRemindLater: () -> Unit = {},
) {
    WhiteNoiseAlertDialog(
        modifier = Modifier.testTag(APP_UPDATE_DIALOG_TAG).semantics { stateDescription = title },
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            AppSelfUpdateCancelActions(
                onCancel,
                stringResource(R.string.app_self_update_cancel),
                canRemindLater,
                onRemindLater,
            )
        },
    )
}

/** Uses platform-localized file sizes and retains the real unknown-size fallback. */
private fun formatApkSize(
    context: android.content.Context,
    bytes: Long?,
): String =
    if (bytes == null || bytes <= 0L) {
        context.getString(R.string.app_self_update_size_unknown)
    } else {
        Formatter.formatShortFileSize(context, bytes)
    }

/** Retains per-version reminders without dismissing a newer discovery or an important update from a stale dialog. */
internal fun remindLaterAppUpdate(
    expectedVersion: String?,
    state: AppSelfUpdateState,
    info: AppUpdateInfo,
    selfUpdateEnabled: Boolean,
    onDismissLatest: () -> Unit,
    onCancel: () -> Unit,
) {
    val reminderAllowed = selfUpdateEnabled && info.isUpdateAvailable && !info.isFarBehind
    if (!reminderAllowed || expectedVersion == null) return
    val matchesPresentedVersion =
        when (state) {
            is AppSelfUpdateState.Confirming -> state.asset.version == expectedVersion
            AppSelfUpdateState.Resolving, is AppSelfUpdateState.Error -> true
            else -> false
        }
    if (!matchesPresentedVersion || info.latestVersion != expectedVersion) return
    onDismissLatest()
    onCancel()
}

/** Keeps Cancel separate from the persisted per-version reminder choice, wrapping both at large font sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming")
private fun AppSelfUpdateCancelActions(
    onCancel: () -> Unit,
    cancelLabel: String,
    canRemindLater: Boolean,
    onRemindLater: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canRemindLater) {
            TextButton(onClick = onRemindLater, modifier = Modifier.testTag("appUpdate.remindLater")) {
                Text(stringResource(R.string.app_self_update_remind_later))
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.testTag("appUpdate.cancel")) { Text(cancelLabel) }
    }
}
