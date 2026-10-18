package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.PushStorageRecovery

/** Explains retained push recovery work without exposing identity, tokens, exceptions, or a destructive reset. */
@Suppress("FunctionNaming")
@Composable
internal fun PushStorageRecoveryControl(
    state: PushStorageRecovery,
    busy: Boolean,
    onRetry: () -> Unit,
) {
    val detail = state.detailResource() ?: return
    SettingsGroup {
        row("push-storage-recovery") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.retry),
                subtitle = stringResource(detail),
                enabled = !busy,
                onClick = onRetry,
            )
        }
    }
}

/** Keeps unreadable saved state, incomplete import, and unrecoverable historical cleanup distinct. */
@StringRes
private fun PushStorageRecovery.detailResource(): Int? =
    when (this) {
        PushStorageRecovery.Checking, PushStorageRecovery.Ready -> null
        PushStorageRecovery.StorageUnavailable -> R.string.notification_push_storage_unavailable
        PushStorageRecovery.LegacyImportPending -> R.string.notification_push_import_pending
        PushStorageRecovery.CleanupReconciliationRequired -> R.string.notification_push_cleanup_unknown
    }
