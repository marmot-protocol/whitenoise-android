@file:Suppress("FunctionNaming") // Compose UI entry points intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.openNotificationChannelSettings
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SettingsGroup
import kotlinx.coroutines.launch

/** Displays account-scoped notification delivery controls and app-wide channel defaults. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    var pendingNotificationEnable by remember { mutableStateOf(false) }
    var pendingBackgroundConnectionEnable by remember { mutableStateOf(false) }
    var pendingNativePushEnable by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.refreshLocalNotificationPermission()
            if (granted && pendingNotificationEnable) {
                appState.launchMutation { appState.setLocalNotificationsEnabled(true) }
            }
            if (granted && pendingBackgroundConnectionEnable) {
                appState.launchMutation { appState.setBackgroundConnectionEnabled(true) }
            }
            if (granted && pendingNativePushEnable) {
                appState.launchMutation { appState.setNativePushEnabled(true) }
            }
            if (!granted) {
                appState.present(R.string.toast_notification_permission_denied)
            }
            pendingNotificationEnable = false
            pendingBackgroundConnectionEnable = false
            pendingNativePushEnable = false
        }

    LaunchedEffect(appState.activeAccountRef) {
        appState.refreshLocalNotificationPermission()
        appState.refreshLocalNotificationSettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SettingsGroup {
                    item {
                        NotificationSwitchRow(
                            title = stringResource(R.string.local_notifications),
                            subtitle = stringResource(R.string.local_notifications_subtitle),
                            icon = Icons.Filled.Notifications,
                            checked = appState.localNotificationSettings?.localNotificationsEnabled == true,
                            enabled = appState.activeAccountRef != null,
                            onCheckedChange = { enabled ->
                                if (enabled && !appState.localNotificationPermissionGranted) {
                                    pendingNotificationEnable = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    appState.launchMutation { appState.setLocalNotificationsEnabled(enabled) }
                                }
                            },
                        )
                    }
                    item {
                        NotificationSwitchRow(
                            title = stringResource(R.string.keep_connected),
                            subtitle = stringResource(R.string.keep_connected_subtitle),
                            icon = Icons.Filled.Sync,
                            checked = appState.backgroundConnectionEnabled,
                            enabled = appState.activeAccountRef != null,
                            onCheckedChange = { enabled ->
                                if (enabled && !appState.localNotificationPermissionGranted) {
                                    pendingBackgroundConnectionEnable = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    appState.launchMutation { appState.setBackgroundConnectionEnabled(enabled) }
                                }
                            },
                        )
                    }
                    item {
                        val nativePushCapability = appState.nativePushCapability()
                        NativePushSettingRow(
                            capability = nativePushCapability,
                            accountReady = appState.activeAccountRef != null,
                            checked = appState.localNotificationSettings?.nativePushEnabled == true,
                            onCheckedChange = { enabled ->
                                if (enabled && !appState.localNotificationPermissionGranted) {
                                    pendingNativePushEnable = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    appState.launchMutation { appState.setNativePushEnabled(enabled) }
                                }
                            },
                        )
                    }
                }
            }
            item {
                GlobalNotificationCategories(
                    onOpenChannel = { channel -> openNotificationChannelSettings(context, channel) },
                )
            }
        }
    }
}

/** Renders native push as available or with its first actionable unsupported cause. */
@Composable
internal fun NativePushSettingRow(
    capability: NativePushCapability,
    accountReady: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    NotificationSwitchRow(
        title = stringResource(R.string.native_push),
        subtitle = stringResource(capability.subtitleResource()),
        icon = Icons.Filled.NotificationsActive,
        checked = capability.isAvailable && checked,
        enabled = capability.isAvailable && accountReady,
        onCheckedChange = onCheckedChange,
    )
}

/** Maps each native-push capability outcome to localized settings copy. */
@StringRes
internal fun NativePushCapability.subtitleResource(): Int =
    when (this) {
        NativePushCapability.MissingPushServerConfiguration -> R.string.native_push_missing_server_subtitle
        NativePushCapability.GooglePlayServicesUnavailable -> R.string.native_push_google_play_unavailable_subtitle
        NativePushCapability.FirebaseUnavailable -> R.string.native_push_firebase_unavailable_subtitle
        NativePushCapability.Available -> R.string.native_push_subtitle
    }

// A toggle row sized to sit inside a segmented SettingsGroup item (the segment
// Surface owns the shape; the row owns its own inset, like a ListItem).
@Composable
private fun NotificationSwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun GlobalNotificationCategories(onOpenChannel: (NotificationChannelSpec) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.notification_defaults_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SettingsGroup(
            title = stringResource(R.string.notification_defaults),
            icon = Icons.Filled.Tune,
        ) {
            NotificationChannelSpec.entries.forEach { channel ->
                item {
                    GlobalNotificationCategoryRow(
                        channel = channel,
                        onClick = { onOpenChannel(channel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalNotificationCategoryRow(
    channel: NotificationChannelSpec,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("global-notification-category-${channel.id}")
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(notificationChannelTitle(channel), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    if (channel == NotificationChannelSpec.APP_UPDATES) {
                        R.string.notification_scope_app_wide
                    } else {
                        R.string.notification_scope_default_all_chats
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.Forward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun notificationChannelTitle(channel: NotificationChannelSpec): String =
    stringResource(
        when (channel) {
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
