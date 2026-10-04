package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

internal data class NotificationPreviewControlState(
    val enabled: Boolean,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

/** One device-wide privacy choice, including before account setup or notification permission. */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationPreviewControl(
    state: NotificationPreviewControlState,
    onChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    SettingsGroup {
        row("previews") { context ->
            SettingsSwitch(
                context = context,
                title = stringResource(R.string.notification_show_previews),
                subtitle = stringResource(R.string.notification_show_previews_detail),
                checked = state.enabled,
                busy = state.busy,
                onCheckedChange = onChange,
            )
        }
        if (state.failed) {
            row("retry") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.retry),
                    subtitle = stringResource(R.string.notification_preview_update_failed),
                    enabled = !state.busy,
                    onClick = onRetry,
                )
            }
        }
    }
}
