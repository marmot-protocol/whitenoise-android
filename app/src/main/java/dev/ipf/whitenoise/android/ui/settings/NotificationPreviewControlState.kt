package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationPreviewMode

internal data class NotificationPreviewControlState(
    val mode: NotificationPreviewMode,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

@StringRes
private fun NotificationPreviewMode.titleRes(): Int =
    when (this) {
        NotificationPreviewMode.FULL -> R.string.notification_preview_full
        NotificationPreviewMode.NAME_ONLY -> R.string.notification_preview_name_only
        NotificationPreviewMode.NONE -> R.string.notification_preview_none
    }

@StringRes
private fun NotificationPreviewMode.detailRes(): Int =
    when (this) {
        NotificationPreviewMode.FULL -> R.string.notification_preview_full_detail
        NotificationPreviewMode.NAME_ONLY -> R.string.notification_preview_name_only_detail
        NotificationPreviewMode.NONE -> R.string.notification_preview_none_detail
    }

/** One device-wide privacy choice (#3659), including before account setup or notification permission. */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationPreviewControl(
    state: NotificationPreviewControlState,
    onChange: (NotificationPreviewMode) -> Unit,
    onRetry: () -> Unit,
) {
    val explanation = stringResource(R.string.explanation_privacy_notif_previews)
    SettingsSection(stringResource(R.string.notification_show_previews))
    SettingsGroup(modifier = Modifier.selectableGroup().testTag("notification-preview.choices")) {
        // Literal keys let the settings search inventory review each choice; search anchors to "previews".
        row("previews") { context ->
            NotificationPreviewChoice(context, NotificationPreviewMode.FULL, state, explanation, onChange)
        }
        row("name_only") { context ->
            NotificationPreviewChoice(context, NotificationPreviewMode.NAME_ONLY, state, explanation, onChange)
        }
        row("none") { context ->
            NotificationPreviewChoice(context, NotificationPreviewMode.NONE, state, explanation, onChange)
        }
        if (state.failed) {
            row("retry") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.retry),
                    subtitle = stringResource(R.string.notification_preview_update_failed),
                    enabled = !state.busy,
                    explanation = stringResource(R.string.explanation_privacy_notif_preview_retry),
                    onClick = onRetry,
                    leading = { SettingsLeadingIcon(R.drawable.ic_replay) },
                )
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun NotificationPreviewChoice(
    context: SettingsRowContext,
    mode: NotificationPreviewMode,
    state: NotificationPreviewControlState,
    explanation: String,
    onChange: (NotificationPreviewMode) -> Unit,
) {
    SettingsChoice(
        context = context,
        title = stringResource(mode.titleRes()),
        subtitle = stringResource(mode.detailRes()),
        explanation = explanation,
        selected = state.mode == mode,
        enabled = !state.busy,
        highlightSelected = false,
        modifier = Modifier.testTag("notification-preview.${mode.storedValue}"),
        onClick = { if (state.mode != mode) onChange(mode) },
    )
}
