package dev.ipf.whitenoise.android.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.openNotificationChannelSettings
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/**
 * Notifications as the prototype lays them out: a permission group until Android allows notifications, the Delivery
 * group (local notifications, native push), the background connection with its explainer, and the Android
 * notification categories. Delivery and background writes stay the production mutations.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun NotificationsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.refreshLocalNotificationPermission()
            permissionDenied = !granted
            if (!granted) appState.present(R.string.toast_notification_permission_denied)
        }

    LaunchedEffect(appState.activeAccountRef) {
        appState.refreshLocalNotificationPermission()
        appState.refreshLocalNotificationSettings()
    }

    val permissionGranted = appState.localNotificationPermissionGranted
    val accountReady = appState.activeAccountRef != null && permissionGranted
    val localEnabled = appState.localNotificationSettings?.localNotificationsEnabled == true
    val backgroundEnabled = appState.backgroundConnectionEnabled

    SettingsScaffold(title = stringResource(R.string.notifications), onBack = onBack) {
        SettingsList {
            if (!permissionGranted) {
                item {
                    NotificationPermissionGroup(
                        denied = permissionDenied,
                        onRequest = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        onOpenSettings = { openAppNotificationSettings(context) },
                    )
                }
            }
            item { SettingsSection(stringResource(R.string.delivery)) }
            item {
                SettingsGroup {
                    row("local_notifications") { rowContext ->
                        SettingsSwitch(
                            context = rowContext,
                            title = stringResource(R.string.local_notifications),
                            subtitle = stringResource(R.string.local_notifications_detail),
                            checked = localEnabled,
                            enabled = accountReady,
                            onCheckedChange = { enabled ->
                                appState.launchMutation { appState.setLocalNotificationsEnabled(enabled) }
                            },
                        )
                    }
                    row("native_push") { rowContext ->
                        NativePushSettingRow(
                            context = rowContext,
                            capability = appState.nativePushCapability(),
                            accountReady = accountReady && localEnabled,
                            checked = appState.localNotificationSettings?.nativePushEnabled == true,
                            onCheckedChange = { enabled ->
                                appState.launchMutation { appState.setNativePushEnabled(enabled) }
                            },
                        )
                    }
                }
            }
            item {
                SettingsGroup {
                    row("background") { rowContext ->
                        SettingsSwitch(
                            context = rowContext,
                            title = stringResource(R.string.keep_connected_in_background),
                            subtitle = stringResource(R.string.keep_connected_in_background_detail),
                            checked = backgroundEnabled,
                            enabled = accountReady,
                            onCheckedChange = { enabled ->
                                appState.launchMutation { appState.setBackgroundConnectionEnabled(enabled) }
                            },
                        )
                    }
                }
            }
            item {
                SettingsExplainer(
                    stringResource(
                        if (backgroundEnabled) {
                            R.string.notification_background_on
                        } else {
                            R.string.notification_background_off
                        },
                    ),
                )
            }
            item { SettingsSection(stringResource(R.string.notification_categories)) }
            item { GlobalNotificationCategories(onOpenChannel = { openNotificationChannelSettings(context, it) }) }
            item { SettingsExplainer(stringResource(R.string.notification_categories_detail)) }
        }
    }
}

/** Until Android allows notifications: a request action, or, once denied, a link into Android's settings. */
@Suppress("FunctionNaming")
@Composable
private fun NotificationPermissionGroup(
    denied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    SettingsGroup(modifier = Modifier.testTag("notifications.permission.group")) {
        if (denied) {
            row("blocked") { context ->
                SettingsLink(
                    context = context,
                    title = stringResource(R.string.notifications_are_off),
                    subtitle = stringResource(R.string.notifications_are_off_detail),
                    onClick = onOpenSettings,
                    leading = { NotificationPermissionIcon(R.drawable.ic_notifications_off) },
                )
            }
        } else {
            row("allow") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.allow_notifications),
                    subtitle = stringResource(R.string.allow_notifications_detail),
                    onClick = onRequest,
                    leading = { NotificationPermissionIcon(R.drawable.ic_settings_notifications) },
                )
            }
        }
    }
}

/** Leading glyph of the permission rows, tinted like the hub icons. */
@Suppress("FunctionNaming")
@Composable
private fun NotificationPermissionIcon(drawable: Int) {
    Icon(
        painter = painterResource(drawable),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Native push as a whole-row switch: on with the prototype's detail when the capability is available, otherwise off,
 * disabled and explaining its first actionable unsupported cause.
 */
@Suppress("FunctionNaming")
@Composable
internal fun NativePushSettingRow(
    context: SettingsRowContext,
    capability: NativePushCapability,
    accountReady: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsSwitch(
        context = context,
        title = stringResource(R.string.native_push_title),
        subtitle =
            stringResource(
                if (capability.isAvailable) R.string.notification_push_detail else capability.subtitleResource(),
            ),
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

/** One link per Android notification channel; each opens that channel's system settings. */
@Suppress("FunctionNaming")
@Composable
internal fun GlobalNotificationCategories(onOpenChannel: (NotificationChannelSpec) -> Unit) {
    SettingsGroup {
        NotificationChannelSpec.entries.forEach { channel ->
            row(channel.id) { context ->
                SettingsLink(
                    context = context,
                    title = notificationChannelTitle(channel),
                    onClick = { onOpenChannel(channel) },
                    modifier = Modifier.testTag("global-notification-category-${channel.id}"),
                )
            }
        }
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

/** Opens Android's notification settings page for this app. */
private fun openAppNotificationSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
