@file:Suppress("FunctionNaming") // Compose UI entry points intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** One settings group per category: the Android settings link, plus the custom-scope switch where allowed. */
@Composable
internal fun ConversationNotificationCategoriesList(
    settings: List<ConversationNotificationCategorySetting>,
    pendingChannel: NotificationChannelSpec? = null,
    onOpen: (ConversationNotificationCategorySetting) -> Unit,
    onScopeChange: (ConversationNotificationCategorySetting, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
        settings.forEach { setting ->
            key(setting.channel.id) {
                ConversationNotificationCategoryGroup(
                    setting = setting,
                    pending = pendingChannel == setting.channel,
                    scopeChangesEnabled = pendingChannel == null,
                    onOpen = onOpen,
                    onScopeChange = onScopeChange,
                )
            }
        }
    }
}

@Composable
private fun ConversationNotificationCategoryGroup(
    setting: ConversationNotificationCategorySetting,
    pending: Boolean,
    scopeChangesEnabled: Boolean,
    onOpen: (ConversationNotificationCategorySetting) -> Unit,
    onScopeChange: (ConversationNotificationCategorySetting, Boolean) -> Unit,
) {
    val usesCustom = setting.scope == ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT
    val title = notificationChannelTitle(setting.channel)
    val toggleDescription = stringResource(R.string.notification_custom_for_chat_control, title)
    val openTag =
        if (
            BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS &&
            setting.channel == NotificationChannelSpec.GROUP_MESSAGES
        ) {
            PerformanceTestTags.GROUP_MESSAGE_NOTIFICATION_SETTINGS
        } else {
            "open-conversation-notification-${setting.channel.id}"
        }
    SettingsGroup(modifier = Modifier.testTag("conversation-notification-category-${setting.channel.id}")) {
        row("open") { context ->
            SettingsLink(
                context = context,
                title = title,
                onClick = { onOpen(setting) },
                modifier = Modifier.testTag(openTag),
                subtitle =
                    stringResource(
                        if (usesCustom) {
                            R.string.notification_scope_custom_chat
                        } else {
                            R.string.notification_scope_default_all_chats
                        },
                    ),
                busy = pending,
            )
        }
        if (setting.canChangeScope) {
            row("scope") { context ->
                SettingsSwitch(
                    context = context,
                    title = stringResource(R.string.notification_scope_custom_chat),
                    checked = usesCustom,
                    onCheckedChange = { useCustom -> onScopeChange(setting, useCustom) },
                    modifier = Modifier.semantics { contentDescription = toggleDescription },
                    subtitle = stringResource(R.string.notification_scope_switch_detail),
                    enabled = scopeChangesEnabled,
                )
            }
        }
    }
}

@Composable
private fun notificationChannelTitle(parent: NotificationChannelSpec): String =
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
        },
    )
