@file:Suppress("FunctionNaming") // Compose UI entry points intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationNotificationCategorySetting
import dev.ipf.whitenoise.android.notifications.ConversationNotificationScope
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.Dimens

@Composable
internal fun ConversationNotificationCategoriesList(
    settings: List<ConversationNotificationCategorySetting>,
    pendingChannel: NotificationChannelSpec? = null,
    onOpen: (ConversationNotificationCategorySetting) -> Unit,
    onScopeChange: (ConversationNotificationCategorySetting, Boolean) -> Unit,
) {
    Column {
        settings.forEach { setting ->
            key(setting.channel.id) {
                ConversationNotificationCategoryRow(
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
private fun ConversationNotificationCategoryRow(
    setting: ConversationNotificationCategorySetting,
    pending: Boolean,
    scopeChangesEnabled: Boolean,
    onOpen: (ConversationNotificationCategorySetting) -> Unit,
    onScopeChange: (ConversationNotificationCategorySetting, Boolean) -> Unit,
) {
    val usesCustom = setting.scope == ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT
    val title = notificationChannelTitle(setting.channel)
    val toggleDescription = stringResource(R.string.notification_custom_for_chat_control, title)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .testTag("conversation-notification-category-${setting.channel.id}")
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        NotificationCategoryStateIcon(pending)
        NotificationCategorySummary(title, usesCustom, Modifier.weight(1f))
        NotificationCategoryActions(
            setting = setting,
            title = title,
            pending = pending,
            scopeChangesEnabled = scopeChangesEnabled,
            usesCustom = usesCustom,
            toggleDescription = toggleDescription,
            onOpen = onOpen,
            onScopeChange = onScopeChange,
        )
    }
}

/** Renders the independent Android-settings action and optional inheritance switch. */
@Composable
private fun NotificationCategoryActions(
    setting: ConversationNotificationCategorySetting,
    title: String,
    pending: Boolean,
    scopeChangesEnabled: Boolean,
    usesCustom: Boolean,
    toggleDescription: String,
    onOpen: (ConversationNotificationCategorySetting) -> Unit,
    onScopeChange: (ConversationNotificationCategorySetting, Boolean) -> Unit,
) {
    IconButton(
        enabled = !pending,
        onClick = { onOpen(setting) },
        modifier =
            Modifier
                .testTag(
                    if (
                        BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS &&
                        setting.channel == NotificationChannelSpec.GROUP_MESSAGES
                    ) {
                        PerformanceTestTags.GROUP_MESSAGE_NOTIFICATION_SETTINGS
                    } else {
                        "open-conversation-notification-${setting.channel.id}"
                    },
                ),
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = stringResource(R.string.open_notification_category_settings, title),
        )
    }
    if (setting.canChangeScope) {
        Switch(
            checked = usesCustom,
            enabled = scopeChangesEnabled,
            onCheckedChange = { useCustom -> onScopeChange(setting, useCustom) },
            modifier = Modifier.semantics { contentDescription = toggleDescription },
        )
    }
}

@Composable
private fun NotificationCategoryStateIcon(pending: Boolean) {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (pending) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotificationCategorySummary(
    title: String,
    usesCustom: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text =
                stringResource(
                    if (usesCustom) {
                        R.string.notification_scope_custom_chat
                    } else {
                        R.string.notification_scope_default_all_chats
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
