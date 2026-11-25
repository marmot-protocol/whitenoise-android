package dev.ipf.whitenoise.android.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Presents the active take through the same foreground record used by dictation. */
internal class VoiceNoteForegroundService(
    private val service: NotificationStreamForegroundService,
) {
    var notification: Notification? = null
        private set
    val serviceType: Int
        get() =
            if (notification == null) {
                0
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }

    private var recorder: VoiceRecordingController? = null
    private var observer: Job? = null

    /** Routes voice-note foreground commands while satisfying any unmet start deadline. */
    @Suppress("ReturnCount") // Stale actions and failed promotion end without observation.
    fun onCommand(
        intent: Intent,
        startId: Int,
        scope: CoroutineScope,
    ) {
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val owner = ownerResolver(service)
        val current = token?.let { owner?.foregroundRecorder(it) }
        if (intent.action != ACTION_START) {
            if (current != null && current === recorder) {
                if (intent.action == ACTION_STOP) current.stop() else current.cancel()
            } else {
                service.foreground.stopIfIdleWithoutPlaceholder(startId)
            }
            return
        }
        service.foreground.noteForegroundStartPending(startId)
        if (token == null || current == null || !current.isRecording) {
            service.foreground.releaseVoice(startId, satisfyStartDeadline = true)
            return
        }

        val promoted =
            runCatching {
                val card = buildNotification(token)
                service.foreground.promoteVoice(card)
                notification = card
            }.isSuccess
        if (!promoted) {
            service.foreground.releaseVoice(startId, satisfyStartDeadline = true)
            current.interrupt()
            return
        }

        observer?.cancel()
        recorder = current
        observer =
            scope.launch {
                snapshotFlow { !current.isRecording && !current.isFinalizing }.first { it }
                recorder = null
                notification = null
                service.foreground.releaseVoice()
            }
    }

    fun onDestroy() {
        observer?.cancel()
        recorder?.interrupt()
        recorder = null
        notification = null
    }

    private fun buildNotification(token: String): Notification {
        val manager = service.getSystemService(NotificationManager::class.java)
        val title = service.getString(R.string.voice_row_recording)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW))
        return Notification
            .Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dictation)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(ACTION_STOP, R.string.voice_recording_stop, token))
            .addAction(action(ACTION_DISCARD, R.string.discard, token))
            .build()
    }

    private fun action(
        command: String,
        label: Int,
        token: String,
    ): Notification.Action {
        val intent =
            Intent(service, NotificationStreamForegroundService::class.java)
                .setAction(command)
                .setIdentifier(token)
                .putExtra(EXTRA_TOKEN, token)
        val pending = PendingIntent.getService(service, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, service.getString(label), pending).build()
    }

    companion object {
        const val CHANNEL_ID = "voice_note_capture"
        internal const val ACTION_START = "dev.ipf.whitenoise.android.voice.START"
        internal const val ACTION_STOP = "dev.ipf.whitenoise.android.voice.STOP"
        internal const val ACTION_DISCARD = "dev.ipf.whitenoise.android.voice.DISCARD"
        internal const val EXTRA_TOKEN = "voice_note_session"

        /** Test seam for resolving the process-owned voice-note session owner. */
        internal var ownerResolver: (NotificationStreamForegroundService) -> VoiceNoteSessionOwner? = { host ->
            (host.application as? WhiteNoiseApplication)?.initializedAppState()?.voiceNotes
        }

        fun isCommand(intent: Intent?): Boolean = intent?.action in setOf(ACTION_START, ACTION_STOP, ACTION_DISCARD)

        fun start(
            context: Context,
            token: String,
        ): Boolean =
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, NotificationStreamForegroundService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_TOKEN, token),
                )
            }.isSuccess
    }
}
