package dev.ipf.whitenoise.android.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationBatteryPolicy
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.openNotificationBatterySettings
import dev.ipf.whitenoise.android.notifications.openNotificationChannelSettings
import dev.ipf.whitenoise.android.state.NotificationDeliveryMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * Presents one delivery policy while retaining Android permission, battery policy, and category controls.
 * The mode rows project existing runtime settings instead of persisting a second Android-owned mode value.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun NotificationsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.refreshLocalNotificationPermission()
            permissionDenied = !granted
            if (granted) {
                appState.launchMutation {
                    appState.refreshLocalNotificationSettings()
                    appState.setNotificationDeliveryMode(appState.notificationDeliveryMode())
                }
            } else {
                appState.present(R.string.toast_notification_permission_denied)
            }
        }

    LaunchedEffect(appState.activeAccountRef) {
        appState.refreshLocalNotificationPermission()
        appState.refreshNotificationBatteryPolicy()
        appState.refreshLocalNotificationSettings()
    }

    DisposableEffect(lifecycleOwner, appState) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    appState.refreshLocalNotificationPermission()
                    appState.refreshNotificationBatteryPolicy()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionGranted = appState.localNotificationPermissionGranted
    val hasAccount = appState.activeAccountRef != null
    val capability = appState.nativePushCapability()
    val selectedMode = appState.notificationDeliveryMode()

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
                NotificationDeliverySelector(
                    selectedMode = selectedMode,
                    capability = capability,
                    enabled = hasAccount && permissionGranted && !appState.notificationDeliveryModeBusy,
                    onSelect = { mode -> appState.launchMutation { appState.setNotificationDeliveryMode(mode) } },
                )
            }
            if (!capability.isAvailable) {
                item { SettingsExplainer(stringResource(capability.subtitleResource())) }
            }
            item { SettingsSection(stringResource(R.string.notification_device_policy)) }
            item {
                NotificationDevicePolicyGroup(
                    permissionGranted = permissionGranted,
                    batteryPolicy = appState.notificationBatteryPolicy,
                    onOpenNotificationSettings = { openAppNotificationSettings(context) },
                    onOpenBatterySettings = {
                        if (!openNotificationBatterySettings(context)) {
                            appState.present(R.string.toast_notification_settings_unavailable)
                        }
                    },
                )
            }
            item { SettingsExplainer(stringResource(R.string.notification_device_policy_detail)) }
            item { SettingsSection(stringResource(R.string.notification_categories)) }
            item { GlobalNotificationCategories(onOpenChannel = { openNotificationChannelSettings(context, it) }) }
            item { SettingsExplainer(stringResource(R.string.notification_categories_detail)) }
        }
    }
}

/** One radio group: local delivery always exists and push delivery exists only when usable. */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationDeliverySelector(
    selectedMode: NotificationDeliveryMode,
    capability: NativePushCapability,
    enabled: Boolean,
    onSelect: (NotificationDeliveryMode) -> Unit,
) {
    SettingsGroup(
        modifier =
            Modifier
                .selectableGroup()
                .padding(top = WhiteNoiseSpacing.Section)
                .testTag("notification-delivery.choices"),
    ) {
        if (capability.isAvailable) {
            row("fcm") { rowContext ->
                SettingsChoice(
                    context = rowContext,
                    title = stringResource(R.string.notification_delivery_push),
                    subtitle = stringResource(R.string.notification_delivery_push_detail),
                    selected = selectedMode == NotificationDeliveryMode.Fcm,
                    enabled = enabled,
                    highlightSelected = false,
                    modifier = Modifier.testTag("notification-delivery.fcm"),
                    onClick = { onSelect(NotificationDeliveryMode.Fcm) },
                )
            }
        }
        row("local") { rowContext ->
            SettingsChoice(
                context = rowContext,
                title = stringResource(R.string.local_notifications),
                subtitle = stringResource(R.string.notification_delivery_local_detail),
                selected = selectedMode == NotificationDeliveryMode.Local,
                enabled = enabled,
                highlightSelected = false,
                modifier = Modifier.testTag("notification-delivery.local"),
                onClick = { onSelect(NotificationDeliveryMode.Local) },
            )
        }
    }
}

/** Live Android permission and background-policy status with explicit user-requested settings actions. */
@Suppress("FunctionNaming")
@Composable
private fun NotificationDevicePolicyGroup(
    permissionGranted: Boolean,
    batteryPolicy: NotificationBatteryPolicy,
    onOpenNotificationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    SettingsGroup {
        row("permission") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.notification_permission_status),
                subtitle =
                    stringResource(
                        if (permissionGranted) {
                            R.string.notification_permission_allowed
                        } else {
                            R.string.notification_permission_blocked
                        },
                    ),
                onClick = onOpenNotificationSettings,
            )
        }
        row("battery") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.notification_battery_policy),
                subtitle = stringResource(batteryPolicy.subtitleResource()),
                onClick = onOpenBatterySettings,
                modifier = Modifier.testTag("notification-battery.settings"),
            )
        }
    }
}

/** Maps the live platform policy to neutral, localized status copy. */
@StringRes
internal fun NotificationBatteryPolicy.subtitleResource(): Int =
    when (this) {
        NotificationBatteryPolicy.Optimized -> R.string.notification_battery_optimized
        NotificationBatteryPolicy.Unrestricted -> R.string.notification_battery_unrestricted
        NotificationBatteryPolicy.Restricted -> R.string.notification_battery_restricted
        NotificationBatteryPolicy.Unknown -> R.string.notification_battery_unknown
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

/** Title of a notification channel. */
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
