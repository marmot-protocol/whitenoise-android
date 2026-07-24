package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationNotificationChannels
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.openConversationNotificationSettings
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.chats.newchat.SettingsActionRow
import dev.ipf.whitenoise.android.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming") // Jetpack Compose screen functions use UpperCamelCase.
@Composable
internal fun ConversationNotificationSettingsScreen(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
    conversationTitle: String,
    conversationAvatarUrl: String?,
    isDm: Boolean,
    notifyMode: ChatNotifyMode,
    onBack: () -> Unit,
    onChooseNotifyMode: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sounds_and_notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(vertical = Dimens.spaceSm),
        ) {
            SectionHeader(stringResource(R.string.notifications))
            SettingsActionRow(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.notify),
                value = notificationModeLabel(notifyMode),
                onClick = onChooseNotifyMode,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = Dimens.spaceSm),
            )
            SectionHeader(stringResource(R.string.notification_categories))
            ConversationNotificationChannels.relevantParents(isDm).forEach { parent ->
                SettingsActionRow(
                    icon = Icons.Default.Settings,
                    title = notificationChannelTitle(parent),
                    value = stringResource(R.string.customize_sound_vibration),
                    onClick =
                        appState.activeAccountRef?.let { accountRef ->
                            {
                                openConversationNotificationSettings(
                                    context = context,
                                    accountRef = accountRef,
                                    groupIdHex = groupIdHex,
                                    isDm = isDm,
                                    parent = parent,
                                    conversationTitle = conversationTitle,
                                    conversationAvatarUrl = conversationAvatarUrl,
                                )
                            }
                        },
                )
            }
        }
    }
}

@Composable
internal fun notificationModeLabel(mode: ChatNotifyMode): String =
    stringResource(
        when (mode) {
            ChatNotifyMode.ALL -> R.string.notify_all_messages
            ChatNotifyMode.MENTIONS_ONLY -> R.string.notify_only_mentions
            ChatNotifyMode.NONE -> R.string.notify_nothing
        },
    )

@Composable
private fun notificationChannelTitle(parent: NotificationChannelSpec): String =
    stringResource(
        when (parent) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages
            NotificationChannelSpec.MENTIONS -> R.string.notification_channel_mentions
            NotificationChannelSpec.REACTIONS -> R.string.notification_channel_reactions
            NotificationChannelSpec.INVITES -> R.string.notification_channel_invites
            NotificationChannelSpec.AGENT_ACTIVITY -> R.string.notification_channel_agent_activity
            NotificationChannelSpec.APP_UPDATES -> R.string.notification_channel_app_updates
        },
    )
