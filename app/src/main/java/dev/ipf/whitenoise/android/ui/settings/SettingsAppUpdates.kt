@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.updates.AppUpdateEmblem
import dev.ipf.whitenoise.android.updates.AppUpdateInfo

/** Self-managed distribution only: one row that checks for or installs the latest release. */
@Composable
@Suppress("FunctionNaming")
internal fun AppUpdateGroup(
    info: AppUpdateInfo,
    onClick: () -> Unit,
    prominent: Boolean,
) {
    SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.FormField).testTag("settings.app_updates")) {
        row("app_updates") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.app_updates),
                subtitle = appUpdateSubtitle(info),
                onClick = onClick,
                // The filled emblem announces something to do; without an update the row keeps the plain
                // glyph every other row leads with.
                leading =
                    if (prominent) {
                        { AppUpdateEmblem() }
                    } else {
                        { SettingsLeadingIcon(R.drawable.ic_system_update) }
                    },
            )
        }
    }
}

/** Concise prototype status uses the real release version and keeps a failed check actionable. */
@Composable
private fun appUpdateSubtitle(info: AppUpdateInfo): String =
    when {
        info.lastAttemptErrorReport != null -> stringResource(R.string.app_update_settings_check_failed)
        info.latestVersion == null -> stringResource(R.string.app_update_row_unknown)
        !info.isUpdateAvailable -> stringResource(R.string.app_update_row_current)
        else -> stringResource(R.string.app_update_row_available, info.latestVersion)
    }

/** Retry failed/unknown/current checks before delegating to the existing verified update flow. */
internal suspend fun runSettingsAppUpdateAction(
    info: AppUpdateInfo,
    refresh: suspend () -> AppUpdateInfo,
    onUpdate: () -> Unit,
) {
    if (!info.isUpdateAvailable || info.lastAttemptErrorReport != null) {
        val refreshed = refresh()
        // A cached newer release must not turn another failed check into an install request.
        if (refreshed.lastAttemptErrorReport != null) return
    }
    onUpdate()
}
