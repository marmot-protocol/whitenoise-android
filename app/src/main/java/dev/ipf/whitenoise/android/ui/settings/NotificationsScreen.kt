package dev.ipf.whitenoise.android.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SectionCard
import kotlinx.coroutines.launch

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
                SectionCard(title = stringResource(R.string.notifications)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.local_notifications), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.local_notifications_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
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
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.keep_connected), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.keep_connected_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
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
                    val nativePushAvailable = appState.isNativePushAvailable()
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.native_push), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(
                                    if (nativePushAvailable) {
                                        R.string.native_push_subtitle
                                    } else {
                                        R.string.native_push_unavailable_subtitle
                                    },
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = nativePushAvailable && appState.localNotificationSettings?.nativePushEnabled == true,
                            enabled = nativePushAvailable && appState.activeAccountRef != null,
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
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    // Per-type controls (sound, vibration, importance, lockscreen,
                    // DND bypass) live in the OS notification details — Android's
                    // native per-channel UI. We deep-link there instead of
                    // duplicating those toggles in-app. See #288.
                    //
                    // Use the same plain Row layout as the toggle rows above (not a
                    // ListItem-based SettingsRow) so the leading edge lines up with
                    // them; ListItem injects its own ~16.dp leading inset that left
                    // the categories row indented further right than the settings
                    // above it. See #499.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openAppNotificationSettings(context) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.notification_categories), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.notification_categories_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.Forward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// Open the OS app-notification details so the user gets native per-channel
// controls (sound, vibration, importance, lockscreen, DND bypass). Falls back
// to the generic app-details screen on the rare device that rejects the
// channel-settings action, and finally toasts if even that fails. See #288.
private fun openAppNotificationSettings(context: Context) {
    val appNotificationIntent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (context.tryStartActivity(appNotificationIntent)) return

    val appDetailsIntent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (context.tryStartActivity(appDetailsIntent)) return

    Toast.makeText(context, R.string.toast_notification_settings_unavailable, Toast.LENGTH_SHORT).show()
}

private fun Context.tryStartActivity(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess
