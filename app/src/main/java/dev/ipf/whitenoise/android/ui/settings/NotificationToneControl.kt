package dev.ipf.whitenoise.android.ui.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.NotificationTone
import dev.ipf.whitenoise.android.notifications.NotificationToneExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Audition and explicit import are separate from the OS-owned category sound selection below.
 * Platform I/O failures become recoverable UI feedback; coroutine cancellation still propagates.
 */
@Suppress("FunctionNaming", "LongMethod", "TooGenericExceptionCaught")
@Composable
internal fun NotificationToneControl() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val preview = remember(context) { NotificationTonePreview(context.applicationContext) }
    var playing by remember { mutableStateOf<NotificationTone?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<Int?>(null) }
    var previewErrors by remember { mutableStateOf(emptySet<NotificationTone>()) }
    DisposableEffect(lifecycle, preview) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    preview.stop()
                    playing = null
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            preview.stop()
        }
    }
    SettingsGroup {
        NotificationTone.entries.forEach { tone ->
            row(tone.token) { rowContext ->
                SettingsAction(
                    context = rowContext,
                    title = stringResource(tone.title),
                    subtitle =
                        stringResource(
                            when {
                                playing == tone -> R.string.notification_tone_stop
                                tone in previewErrors -> R.string.notification_tone_preview_failed
                                else -> R.string.notification_tone_preview
                            },
                        ),
                    modifier = Modifier.testTag("notification-tone-${tone.token}"),
                    onClick = {
                        if (playing == tone) {
                            preview.stop()
                            playing = null
                        } else {
                            playing = null
                            runCatching {
                                preview.play(
                                    tone,
                                    onStarted = { previewErrors = previewErrors - tone },
                                    onFailure = {
                                        playing = null
                                        previewErrors = previewErrors + tone
                                    },
                                ) { playing = null }
                                playing = tone
                            }.onFailure { previewErrors = previewErrors + tone }
                        }
                    },
                )
            }
        }
        row("import") { rowContext ->
            SettingsLink(
                context = rowContext,
                title = stringResource(R.string.notification_tone_add),
                subtitle = stringResource(importResult ?: R.string.notification_tone_add_detail),
                busy = importing,
                modifier = Modifier.testTag("notification-tone-import"),
                onClick = {
                    if (!importing) {
                        importing = true
                        preview.stop()
                        playing = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    NotificationToneExport.addToAndroid(context.applicationContext)
                                }
                                importResult = R.string.notification_tone_added
                            } catch (failure: Exception) {
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                importResult = R.string.notification_tone_add_failed
                            } finally {
                                importing = false
                            }
                        }
                    }
                },
            )
        }
    }
}

/** Refresh actual OS sound, rather than retaining a competing in-app selected-tone preference. */
@Suppress("FunctionNaming")
@Composable
internal fun rememberNotificationSoundLabels(): Map<NotificationChannelSpec, String> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var labels by remember { mutableStateOf<Map<NotificationChannelSpec, String>>(emptyMap()) }
    DisposableEffect(lifecycle, context) {
        var refreshJob: Job? = null

        fun refresh() {
            refreshJob?.cancel()
            refreshJob =
                scope.launch {
                    val previous = labels
                    labels =
                        withContext(Dispatchers.IO) {
                            notificationSoundLabelsOrPrevious(previous) { readNotificationSoundLabels(context) }
                        }
                }
        }
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            refreshJob?.cancel()
        }
    }
    return labels
}

/** Optional labels keep their last observation on a transient platform failure; cancellation must still stop work. */
internal fun notificationSoundLabelsOrPrevious(
    previous: Map<NotificationChannelSpec, String>,
    read: () -> Map<NotificationChannelSpec, String>,
): Map<NotificationChannelSpec, String> =
    try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        previous
    }

/** Reads the whole OS channel: retained sound URIs do not mean a blocked/silent channel can alert. */
internal fun readNotificationSoundLabels(context: Context): Map<NotificationChannelSpec, String> {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyMap()
    val appEnabled = manager.areNotificationsEnabled()
    return NotificationChannelSpec.entries
        .mapNotNull { spec ->
            manager.getNotificationChannel(spec.id)?.let { channel ->
                val groupBlocked = channel.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true
                spec to notificationChannelSoundLabel(context, channel, appEnabled && !groupBlocked)
            }
        }.toMap()
}

private fun notificationChannelSoundLabel(
    context: Context,
    channel: NotificationChannel,
    appEnabled: Boolean,
): String =
    when {
        !appEnabled || channel.importance == NotificationManager.IMPORTANCE_NONE ->
            context.getString(R.string.notification_tone_off)
        channel.importance < NotificationManager.IMPORTANCE_DEFAULT || channel.sound == null ->
            context.getString(R.string.notification_tone_silent)
        else -> notificationSoundLabel(context, channel.sound)
    }

/** Resolve display metadata only; constructing a Ringtone would prepare and retain an audio player. */
private fun notificationSoundLabel(
    context: Context,
    uri: Uri?,
): String {
    val tone = uri?.let { NotificationTone.resolve(context, it) }
    return when {
        uri == null -> context.getString(R.string.notification_tone_silent)
        uri == Settings.System.DEFAULT_NOTIFICATION_URI -> context.getString(R.string.notification_tone_default)
        tone != null -> context.getString(tone.title)
        else ->
            runCatching {
                context.contentResolver
                    .query(
                        uri,
                        arrayOf(MediaStore.Audio.Media.TITLE, OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            listOf(MediaStore.Audio.Media.TITLE, OpenableColumns.DISPLAY_NAME)
                                .firstNotNullOfOrNull { column ->
                                    cursor.getColumnIndex(column).takeIf { it >= 0 }?.let { index ->
                                        cursor.getString(index)?.takeIf { it.isNotBlank() }
                                    }
                                }
                        } else {
                            null
                        }
                    }
            }.getOrNull() ?: context.getString(R.string.notification_tone_android)
    }
}
