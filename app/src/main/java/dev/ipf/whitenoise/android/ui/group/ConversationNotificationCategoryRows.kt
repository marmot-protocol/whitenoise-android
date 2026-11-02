@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationNotificationCategorySetting
import dev.ipf.whitenoise.android.notifications.ConversationNotificationScope
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Channel customization and alert delivery are independent actions in one compact row. */
@Suppress("LongParameterList") // One model with independent platform-navigation and delivery actions.
@Composable
internal fun ConversationNotificationCategoriesList(
    settings: List<ConversationNotificationCategorySetting>,
    pendingChannel: NotificationChannelSpec? = null,
    onOpen: (ConversationNotificationCategorySetting) -> Unit,
    alertEnabled: Map<NotificationChannelSpec, Boolean> = emptyMap(),
    alertDetails: Map<NotificationChannelSpec, String> = emptyMap(),
    onAlertChange: (ConversationNotificationCategorySetting, Boolean) -> Unit = { _, _ -> },
) {
    SettingsGroup {
        settings.forEach { setting ->
            row(setting.channel.id) { context ->
                val title = notificationChannelTitle(setting.channel)
                SettingsLink(
                    context = context,
                    title = title,
                    onClick = { onOpen(setting) },
                    modifier =
                        Modifier.testTag(
                            if (BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS &&
                                setting.channel == NotificationChannelSpec.GROUP_MESSAGES
                            ) {
                                PerformanceTestTags.GROUP_MESSAGE_NOTIFICATION_SETTINGS
                            } else {
                                "open-conversation-notification-${setting.channel.id}"
                            },
                        ),
                    subtitle =
                        listOfNotNull(
                            stringResource(
                                if (setting.scope == ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT) {
                                    R.string.notification_scope_custom_chat
                                } else {
                                    R.string.notification_scope_default_all_chats
                                },
                            ),
                            alertDetails[setting.channel]?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                    busy = pendingChannel == setting.channel,
                    trailing = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            alertEnabled[setting.channel]?.let { enabled ->
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { onAlertChange(setting, it) },
                                    enabled = pendingChannel == null,
                                    modifier =
                                        Modifier
                                            .testTag("conversation-alert-${setting.channel.id}")
                                            .semantics { contentDescription = title },
                                )
                            }
                            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun notificationChannelTitle(parent: NotificationChannelSpec): String =
    stringResource(
        when (parent) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages
            NotificationChannelSpec.MENTIONS -> R.string.notification_channel_mentions
            NotificationChannelSpec.REACTIONS -> R.string.notification_channel_reactions
            NotificationChannelSpec.INVITES -> R.string.notification_channel_invites
            NotificationChannelSpec.GROUP_MEMBERSHIP -> R.string.notification_channel_group_membership
            NotificationChannelSpec.AGENT_ACTIVITY -> R.string.notification_channel_agent_activity
            NotificationChannelSpec.APP_UPDATES -> R.string.notification_channel_app_updates
            NotificationChannelSpec.USER_EVENT_SUMMARY -> R.string.notification_channel_summary
        },
    )
