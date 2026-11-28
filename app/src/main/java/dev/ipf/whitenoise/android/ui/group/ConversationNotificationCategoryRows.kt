@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.DrawableRes
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
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Channel customization and alert delivery are independent actions in one compact row. */
@Suppress("LongParameterList", "LongMethod") // One model with independent platform-navigation and delivery actions.
@Composable
internal fun ConversationNotificationCategoriesList(
    settings: List<ConversationNotificationCategorySetting>,
    pendingChannel: NotificationChannelSpec? = null,
    onOpen: (ConversationNotificationCategorySetting) -> Unit,
    alertEnabled: Map<NotificationChannelSpec, Boolean> = emptyMap(),
    alertDetails: Map<NotificationChannelSpec, String> = emptyMap(),
    onAlertChange: (ConversationNotificationCategorySetting, Boolean) -> Unit = { _, _ -> },
    destination: Any? = null,
) {
    SettingsGroup(destination = destination, surface = "conversation-notification-categories") {
        settings.forEach { setting ->
            row(setting.channel.id) { context ->
                val title = notificationChannelTitle(setting.channel)
                val categoryExplanation = stringResource(R.string.explanation_privacy_conversation_category)
                val alertExplanation = stringResource(R.string.explanation_privacy_conversation_alert)
                // A hold on the row would also win over the switch, so with a switch the row keeps only the
                // TalkBack action and the chevron carries the category hold.
                val hasAlertSwitch = alertEnabled[setting.channel] != null
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
                    explanation = categoryExplanation,
                    holdToExplain = !hasAlertSwitch,
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
                                            .explainOnLongPress(Explanation(title, alertExplanation))
                                            .semantics { contentDescription = title },
                                )
                            }
                            Icon(
                                painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .testTag("conversation-category-chevron-${setting.channel.id}")
                                        .explainOnLongPress(
                                            Explanation(title, categoryExplanation).takeIf { hasAlertSwitch },
                                        ),
                            )
                        }
                    },
                    leading = { SettingsLeadingIcon(notificationChannelIcon(setting.channel)) },
                )
            }
        }
    }
}

/** One glyph per channel, shared by the global and per-conversation category lists. */
@DrawableRes
internal fun notificationChannelIcon(channel: NotificationChannelSpec): Int =
    when (channel) {
        NotificationChannelSpec.DIRECT_MESSAGES -> R.drawable.ic_person
        NotificationChannelSpec.GROUP_MESSAGES -> R.drawable.ic_group
        NotificationChannelSpec.MENTIONS -> R.drawable.ic_alternate_email
        NotificationChannelSpec.REACTIONS -> R.drawable.ic_add_reaction
        NotificationChannelSpec.INVITES -> R.drawable.ic_group_add
        NotificationChannelSpec.GROUP_MEMBERSHIP -> R.drawable.ic_supervisor_account
        NotificationChannelSpec.APP_UPDATES -> R.drawable.ic_system_update
        NotificationChannelSpec.SIGN_IN_REVIEW, NotificationChannelSpec.ACCOUNT_SECURITY -> R.drawable.ic_lock
        NotificationChannelSpec.USER_EVENT_SUMMARY -> R.drawable.ic_summarize
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
            NotificationChannelSpec.APP_UPDATES -> R.string.notification_channel_app_updates
            NotificationChannelSpec.SIGN_IN_REVIEW -> R.string.sign_in_alert_badge_review
            NotificationChannelSpec.ACCOUNT_SECURITY -> R.string.notification_channel_account_security
            NotificationChannelSpec.USER_EVENT_SUMMARY -> R.string.notification_channel_summary
        },
    )
