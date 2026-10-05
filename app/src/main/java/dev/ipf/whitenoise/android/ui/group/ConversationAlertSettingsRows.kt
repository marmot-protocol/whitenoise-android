@file:Suppress("FunctionNaming") // Compose UI entry points intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.ConversationAlertPreferences
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationAlertPreferences

internal data class ConversationAlertSetting(
    val channel: NotificationChannelSpec,
    val enabled: Boolean,
    val blockedByAndroid: Boolean = false,
    val pausedByMute: Boolean = false,
)

/** Binary alert choices, with the same controls for direct and group conversations. */
@Composable
internal fun ConversationAlertSettingsRows(
    settings: List<ConversationAlertSetting>,
    busy: Boolean,
    onChange: (NotificationChannelSpec, Boolean) -> Unit,
) {
    SettingsGroup {
        settings.forEach { setting ->
            row(setting.channel.id) { context ->
                SettingsSwitch(
                    context = context,
                    title = notificationChannelTitle(setting.channel),
                    checked = setting.enabled,
                    enabled = !busy,
                    modifier = Modifier.testTag("conversation-alert-${setting.channel.id}"),
                    onCheckedChange = { onChange(setting.channel, it) },
                    subtitle = stringResource(
                        if (setting.blockedByAndroid) {
                            R.string.notification_system_blocked
                        } else if (setting.pausedByMute) {
                            R.string.notification_alert_paused
                        } else {
                            R.string.notification_alerts_on_device
                        },
                    ),
                )
            }
        }
    }
}

/** Do not advertise a legacy All/Only-mentions summary after independent choices were made. */
@Composable
internal fun conversationNotificationSummary(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
    legacyMode: ChatNotifyMode,
): String? {
    val preferences = appState.conversationAlertPreferences
    val state by preferences.state.collectAsStateWithLifecycle()
    val accountRef = appState.activeAccountRef
    val hasChoices = remember(state, accountRef, groupIdHex) {
        accountRef != null && ConversationAlertPreferences.supportedChannels.any {
            preferences.choice(accountRef, groupIdHex, it) != null
        }
    }
    return when {
        legacyMode == ChatNotifyMode.NONE -> stringResource(
            if (accountRef != null && preferences.choice(accountRef, groupIdHex, NotificationChannelSpec.MENTIONS) == false) {
                R.string.notify_nothing
            } else {
                R.string.notify_nothing_while_muted
            },
        )
        hasChoices -> null
        else -> notificationModeLabel(legacyMode)
    }
}
