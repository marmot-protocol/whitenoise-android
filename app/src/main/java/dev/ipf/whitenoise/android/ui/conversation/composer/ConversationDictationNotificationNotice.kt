@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationForegroundService

internal const val DICTATION_NOTIFICATION_NOTICE_TAG = "dictation-notification-notice"

/** Offers recovery without requiring notifications for in-app dictation or changing system settings. */
@Composable
internal fun ConversationDictationNotificationNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var available by remember(context) {
        mutableStateOf(ConversationDictationForegroundService.notificationControlsAvailable(context))
    }
    DisposableEffect(context, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    available = ConversationDictationForegroundService.notificationControlsAvailable(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (available) return
    ConversationDictationNotificationNoticeContent(
        modifier = modifier,
        onOpenSettings = {
            val appNotificationsEnabled =
                context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
            runCatching {
                context.startActivity(
                    Intent(
                        if (appNotificationsEnabled) {
                            Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                        } else {
                            Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        },
                    ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, ConversationDictationForegroundService.CHANNEL_ID)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

/** A wrapping notice keeps recovery readable at large font without shrinking the three capture actions. */
@Composable
internal fun ConversationDictationNotificationNoticeContent(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth().testTag(DICTATION_NOTIFICATION_NOTICE_TAG),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.dictation_notification_unavailable),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
        }
    }
}
