package dev.ipf.whitenoise.android.ui.group

import android.app.NotificationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.notifications.ConversationNotificationChannels
import dev.ipf.whitenoise.android.notifications.ConversationNotificationChannels.ConversationChannelStatus
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.openConversationNotificationSettings
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.chats.newchat.SettingsActionRow
import dev.ipf.whitenoise.android.ui.theme.Dimens

private const val MILLIS_PER_SECOND = 1_000L
private const val STATUS_SEPARATOR = " · "
private val MUTE_ROW_MIN_HEIGHT = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming") // Jetpack Compose screen functions use UpperCamelCase.
@Composable
internal fun ConversationNotificationSettingsScreen(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
    conversationTitle: String,
    conversationAvatarUrl: String?,
    isDm: Boolean,
    isMuted: Boolean,
    muteExpiryMillis: Long?,
    notifyForMode: ChatNotifyMode,
    onBack: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onChooseNotifyFor: () -> Unit,
) {
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
            MuteSwitchRow(
                muted = isMuted,
                mutedUntil = if (isMuted && muteExpiryMillis != null) mutedUntilLabel(muteExpiryMillis) else null,
                onToggle = onToggleMute,
            )
            SettingsActionRow(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.notify_for),
                value = notificationModeLabel(notifyForMode),
                onClick = onChooseNotifyFor,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = Dimens.spaceSm),
            )
            SectionHeader(stringResource(R.string.notification_categories))
            NotificationCategoriesSection(
                appState = appState,
                groupIdHex = groupIdHex,
                conversationTitle = conversationTitle,
                conversationAvatarUrl = conversationAvatarUrl,
                isDm = isDm,
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun NotificationCategoriesSection(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
    conversationTitle: String,
    conversationAvatarUrl: String?,
    isDm: Boolean,
) {
    val context = LocalContext.current
    val accountRef = appState.activeAccountRef
    val shortcutId = accountRef?.let { conversationShortcutId(it, groupIdHex) }
    // The rows report OS-owned state that the user edits in system settings,
    // so re-read it every time this screen comes back to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshToken++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val fallbackValue = stringResource(R.string.customize_sound_vibration)
    ConversationNotificationChannels.relevantParents(isDm).forEach { parent ->
        val status =
            remember(refreshToken, shortcutId, parent) {
                shortcutId?.let {
                    ConversationNotificationChannels.conversationChannelStatus(context, parent.id, it)
                }
            }
        SettingsActionRow(
            icon = Icons.Default.Settings,
            title = notificationChannelTitle(parent),
            value = status?.let { conversationChannelStatusValue(it) } ?: fallbackValue,
            onClick =
                accountRef?.let {
                    {
                        openConversationNotificationSettings(
                            context = context,
                            accountRef = it,
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

/**
 * String resources describing a conversation channel's live alerting state,
 * ordered most to least significant.
 */
internal fun conversationChannelStatusLabels(status: ConversationChannelStatus): List<Int> =
    buildList {
        add(
            when {
                status.importance <= NotificationManager.IMPORTANCE_NONE -> R.string.notification_importance_off
                status.importance <= NotificationManager.IMPORTANCE_LOW -> R.string.notification_importance_silent
                status.importance == NotificationManager.IMPORTANCE_DEFAULT -> R.string.notification_importance_sound
                else -> R.string.notification_importance_pop_up
            },
        )
        if (status.importantConversation) add(R.string.notification_importance_priority)
        if (status.userSetImportance) add(R.string.notification_importance_customized)
    }

@Composable
private fun conversationChannelStatusValue(status: ConversationChannelStatus): String =
    conversationChannelStatusLabels(status)
        .map { stringResource(it) }
        .joinToString(STATUS_SEPARATOR)

@Composable
private fun mutedUntilLabel(expiryMillis: Long): String =
    stringResource(
        R.string.notify_muted_until,
        IdentityFormatter.clockTime((expiryMillis / MILLIS_PER_SECOND).toULong()),
    )

@Suppress("FunctionNaming")
@Composable
private fun MuteSwitchRow(
    muted: Boolean,
    mutedUntil: String?,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MUTE_ROW_MIN_HEIGHT)
                .toggleable(value = muted, role = Role.Switch, onValueChange = onToggle)
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        Icon(
            Icons.Default.NotificationsOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
            Text(stringResource(R.string.mute), style = MaterialTheme.typography.bodyLarge)
            if (mutedUntil != null) {
                Text(
                    mutedUntil,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = muted, onCheckedChange = null)
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
