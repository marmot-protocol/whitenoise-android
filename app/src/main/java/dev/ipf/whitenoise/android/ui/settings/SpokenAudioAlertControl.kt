package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.SpokenAudioAlertPreferences

/** The device-wide choice to keep message alerts silent while a podcast or audiobook plays (#1717). */
@Suppress("FunctionNaming")
@Composable
internal fun SpokenAudioAlertControl() {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(SpokenAudioAlertPreferences.enabled(context)) }
    SpokenAudioAlertSwitch(
        checked = enabled,
        onCheckedChange = { value ->
            SpokenAudioAlertPreferences.setEnabled(context, value)
            enabled = value
        },
    )
}

/** One switch row; the caller owns the stored value. */
@Suppress("FunctionNaming")
@Composable
internal fun SpokenAudioAlertSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsGroup {
        row("spoken-audio") { rowContext ->
            SettingsSwitch(
                context = rowContext,
                title = stringResource(R.string.notification_quiet_during_spoken_audio),
                subtitle = stringResource(R.string.notification_quiet_during_spoken_audio_detail),
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag("notification-quiet-spoken-audio"),
            )
        }
    }
}
