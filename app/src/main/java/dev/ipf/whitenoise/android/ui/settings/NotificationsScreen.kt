@file:Suppress("TooManyFunctions")

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
import dev.ipf.whitenoise.android.notifications.PushStorageRecovery
import dev.ipf.whitenoise.android.notifications.openNotificationBatterySettings
import dev.ipf.whitenoise.android.notifications.openNotificationChannelSettings
import dev.ipf.whitenoise.android.state.NotificationDeliveryMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Refreshes Android permission, battery policy and local settings now and whenever the screen resumes. */
@Suppress("FunctionNaming")
@Composable
private fun NotificationStateRefresh(appState: WhiteNoiseAppState) {
    val lifecycleOwner = LocalLifecycleOwner.current
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
                    appState.launchMutation { appState.refreshPushStorageRecovery() }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Shows the request or blocked row only until Android allows notifications. */
@Suppress("FunctionNaming")
@Composable
private fun NotificationPermissionPrompt(appState: WhiteNoiseAppState) {
    val context = LocalContext.current
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.refreshLocalNotificationPermission()
            permissionDenied = !granted
            if (granted) {
                appState.launchMutation {
                    appState.refreshLocalNotificationSettings()
                }
            } else {
                appState.present(R.string.toast_notification_permission_denied)
            }
        }
    if (!appState.localNotificationPermissionGranted) {
        NotificationPermissionGroup(
            denied = permissionDenied,
            onRequest = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onOpenSettings = { openAppNotificationSettings(context) },
        )
    }
}

/**
 * Notifications overview: the preview switch and links to the sound, delivery and Android settings pages.
 * Detailed controls live on the child pages so this screen stays short.
 */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenSounds: () -> Unit = {},
    onOpenDelivery: () -> Unit = {},
) {
    val context = LocalContext.current
    NotificationStateRefresh(appState)
    val deliveryValue =
        stringResource(
            when (appState.notificationDeliveryMode()) {
                NotificationDeliveryMode.Fcm -> R.string.notification_delivery_value_push
                NotificationDeliveryMode.Local -> R.string.notification_delivery_value_local
            },
        )

    SettingsScaffold(title = stringResource(R.string.notifications), onBack = onBack) {
        SettingsList {
            if (!appState.localNotificationPermissionGranted) {
                item { NotificationPermissionPrompt(appState) }
            }
            item(key = "search.Notifications.1") {
                val previews = appState.notificationPreviewSettings
                NotificationPreviewControl(
                    state = NotificationPreviewControlState(previews.enabled, previews.busy, previews.failed),
                    onChange = { value -> appState.launchMutation { previews.setEnabled(value) } },
                    onRetry = { appState.launchMutation { previews.retry() } },
                )
            }
            item(key = "search.Notifications.2") {
                SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Section)) {
                    row("sounds") { rowContext ->
                        SettingsLink(
                            context = rowContext,
                            title = stringResource(R.string.notification_sounds_vibration),
                            onClick = onOpenSounds,
                            modifier = Modifier.testTag("notifications.sounds"),
                            explanation = stringResource(R.string.explanation_privacy_notif_sounds),
                        )
                    }
                    row("delivery") { rowContext ->
                        SettingsLink(
                            context = rowContext,
                            title = stringResource(R.string.notification_delivery_battery),
                            value = deliveryValue,
                            onClick = onOpenDelivery,
                            modifier = Modifier.testTag("notifications.delivery"),
                            explanation = stringResource(R.string.explanation_privacy_notif_delivery),
                        )
                    }
                    row("android_settings") { rowContext ->
                        SettingsLink(
                            context = rowContext,
                            title = stringResource(R.string.android_notification_settings),
                            onClick = { openAppNotificationSettings(context) },
                            modifier = Modifier.testTag("notifications.android-settings"),
                            explanation = stringResource(R.string.explanation_privacy_notif_android_settings),
                        )
                    }
                }
            }
        }
    }
}

/** Notification categories, the bundled White Noise sounds and the podcast quiet switch. */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationSoundsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    NotificationStateRefresh(appState)
    val soundLabels = rememberNotificationSoundLabels()

    SettingsScaffold(title = stringResource(R.string.notification_sounds_vibration), onBack = onBack) {
        SettingsList {
            item(key = "search.NotificationSounds.1") {
                SettingsSection(stringResource(R.string.notification_categories))
            }
            item(key = "search.NotificationSounds.2") {
                GlobalNotificationCategories(
                    onOpenChannel = { openNotificationChannelSettings(context, it) },
                    soundLabels = soundLabels,
                )
            }
            item(key = "search.NotificationSounds.3") {
                SettingsExplainer(stringResource(R.string.notification_categories_detail))
            }
            item(key = "search.NotificationSounds.4") { SettingsSection(stringResource(R.string.notification_tones)) }
            item(key = "search.NotificationSounds.5") { NotificationToneControl() }
            item(key = "search.NotificationSounds.6") {
                SettingsExplainer(stringResource(R.string.notification_tone_scope))
            }
            item(key = "search.NotificationSounds.7") { SpokenAudioAlertControl() }
        }
    }
}

/** Delivery method (push or local) plus the Android permission and battery controls that affect it. */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationDeliveryScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    NotificationStateRefresh(appState)
    val permissionGranted = appState.localNotificationPermissionGranted
    val hasAccount = appState.activeAccountRef != null
    val capability = appState.nativePushCapability()

    SettingsScaffold(title = stringResource(R.string.notification_delivery_battery), onBack = onBack) {
        SettingsList {
            if (!permissionGranted) {
                item { NotificationPermissionPrompt(appState) }
            }
            item(key = "search.NotificationDelivery.1") { SettingsSection(stringResource(R.string.delivery)) }
            item(key = "search.NotificationDelivery.2") {
                NotificationDeliverySelector(
                    selectedMode = appState.notificationDeliveryMode(),
                    capability = capability,
                    enabled = hasAccount && permissionGranted && !appState.notificationDeliveryModeBusy,
                    onSelect = { mode -> appState.launchMutation { appState.setNotificationDeliveryMode(mode) } },
                )
            }
            if (
                appState.pushStorageRecovery != PushStorageRecovery.Checking &&
                appState.pushStorageRecovery != PushStorageRecovery.Ready
            ) {
                item(key = "search.NotificationDelivery.3") {
                    PushStorageRecoveryControl(
                        state = appState.pushStorageRecovery,
                        busy = appState.pushStorageRecoveryBusy,
                        onRetry = { appState.launchMutation { appState.retryPushStorageRecovery() } },
                    )
                }
            }
            if (!capability.isAvailable) {
                item(key = "search.NotificationDelivery.4") {
                    SettingsExplainer(stringResource(capability.subtitleResource()))
                }
            }
            item(key = "search.NotificationDelivery.5") {
                SettingsSection(stringResource(R.string.notification_android_controls))
            }
            item(key = "search.NotificationDelivery.6") {
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
            item(key = "search.NotificationDelivery.7") {
                SettingsExplainer(stringResource(R.string.notification_device_policy_detail))
            }
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
                    explanation = stringResource(R.string.explanation_notifications_push),
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
                explanation = stringResource(R.string.explanation_notifications_local),
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
                explanation = stringResource(R.string.explanation_privacy_notif_permission),
            )
        }
        row("battery") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.notification_battery_policy),
                subtitle = stringResource(batteryPolicy.subtitleResource()),
                onClick = onOpenBatterySettings,
                modifier = Modifier.testTag("notification-battery.settings"),
                explanation = stringResource(R.string.explanation_privacy_notif_battery),
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
                    explanation = stringResource(R.string.explanation_privacy_notif_off),
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
                    explanation = stringResource(R.string.explanation_privacy_notif_allow),
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
internal fun GlobalNotificationCategories(
    onOpenChannel: (NotificationChannelSpec) -> Unit,
    soundLabels: Map<NotificationChannelSpec, String> = emptyMap(),
) {
    SettingsGroup {
        NotificationChannelSpec.entries.filter { it != NotificationChannelSpec.USER_EVENT_SUMMARY }.forEach { channel ->
            row(channel.id) { context ->
                SettingsLink(
                    context = context,
                    title = notificationChannelTitle(channel),
                    value = soundLabels[channel],
                    onClick = { onOpenChannel(channel) },
                    modifier = Modifier.testTag("global-notification-category-${channel.id}"),
                    explanation = stringResource(R.string.explanation_privacy_notif_category),
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
            NotificationChannelSpec.USER_EVENT_SUMMARY -> R.string.notification_channel_summary
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
