package dev.ipf.whitenoise.android.audio.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * What the playback service needs from the app: the controller it mirrors and
 * the sentence-level system transport it forwards. An interface so service behavior is
 * testable with a real [TtsController] and no application singleton.
 */
internal interface TtsPlaybackSessionHost {
    val controller: TtsController

    /** Advances the system-media cursor by one logical sentence. */
    fun nextSentence()

    /** Moves the system-media cursor to the previous logical sentence. */
    fun previousSentence()

    /** Ends playback and clears the shared history session. */
    fun stopSession()
}

/** Platform transport callback that forwards every action to the shared app session. */
internal class TtsPlaybackMediaSessionCallback(
    private val host: TtsPlaybackSessionHost,
) : MediaSession.Callback() {
    /** Resumes the paused controller without rebuilding its queue. */
    override fun onPlay() {
        host.controller.resume()
    }

    /** Pauses the shared controller while retaining its sentence cursor. */
    override fun onPause() {
        host.controller.pause()
    }

    /** Ends both platform playback and the app-owned history session. */
    override fun onStop() {
        host.stopSession()
    }

    /** Advances one logical sentence, paging history at a message edge when needed. */
    override fun onSkipToNext() {
        host.nextSentence()
    }

    /** Moves back one logical sentence, paging history at a message edge when needed. */
    override fun onSkipToPrevious() {
        host.previousSentence()
    }
}

/**
 * `mediaPlayback` foreground service that keeps a read-aloud session alive
 * across app switches and screen off, and exposes it as one MediaSession so
 * the notification shade, lock screen, and headset/Bluetooth buttons operate
 * the same session the in-app transport does (#1484).
 *
 * The service owns no queue state: [dev.ipf.whitenoise.android.audio.tts.TtsController]
 * (via the app state) stays the single source of truth, and this class
 * only mirrors its state and forwards transport commands. Notification and
 * MediaSession metadata are deliberately generic — no sender names, chat
 * titles, or message text ever reach the shade or lock screen.
 *
 * Lifecycle: the app state starts the service when a session starts;
 * the service stops itself when the controller goes terminal (natural
 * completion, explicit stop, error — the queue is already cleared in all
 * three). Pause keeps the service and its notification alive: a paused
 * session does not expire merely because time passed or the app was
 * backgrounded.
 */
@Suppress("TooManyFunctions") // Service callbacks + one builder per surface share this lifecycle owner.
class TtsPlaybackForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("ReturnCount")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val host = hostResolver(this)
        val model =
            host
                ?.controller
                ?.state
                ?.value
                ?.let(TtsPlaybackSessionModel::from)
                ?: TtsPlaybackSessionModel.from(TtsState.Idle())
        // Every start enters through startForegroundService(), so promote before
        // any early stop to satisfy Android's foreground-service deadline even
        // when the host is unavailable or the session ended during startup.
        startForeground(NOTIFICATION_ID, buildNotification(model))
        if (host == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == null && !model.isActive) {
            // Started for a session that already ended before the service came
            // up — do not park a stale notification. Stopping the service also
            // removes the foreground notification.
            stopSelf()
            return START_NOT_STICKY
        }
        ensureSession(host)
        observe(host)
        intent?.action?.let { dispatchAction(host, it) }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away is an explicit dismissal of the whole app; a
        // voice that keeps reading private messages afterwards would be a
        // louder privacy failure than the lost convenience.
        hostResolver(this)?.stopSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observeJob?.cancel()
        serviceScope.cancel()
        hostResolver(this)
            ?.takeIf { TtsPlaybackSessionModel.from(it.controller.state.value).isActive }
            ?.stopSession()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    /** Routes notification intents through the same sentence-level host as MediaSession. */
    private fun dispatchAction(
        host: TtsPlaybackSessionHost,
        action: String,
    ) {
        when (action) {
            ACTION_PLAY -> host.controller.resume()
            ACTION_PAUSE -> host.controller.pause()
            ACTION_STOP, ACTION_DISMISS -> host.stopSession()
            ACTION_NEXT_SENTENCE -> host.nextSentence()
            ACTION_PREVIOUS_SENTENCE -> host.previousSentence()
        }
    }

    /** Creates the single platform session that mirrors the app-owned controller. */
    private fun ensureSession(host: TtsPlaybackSessionHost) {
        if (mediaSession != null) return
        mediaSession =
            MediaSession(this, SESSION_TAG).apply {
                setCallback(TtsPlaybackMediaSessionCallback(host))
                setMetadata(
                    MediaMetadata
                        .Builder()
                        .putString(
                            MediaMetadata.METADATA_KEY_TITLE,
                            getString(R.string.tts_playback_notification_title),
                        ).putString(
                            MediaMetadata.METADATA_KEY_ARTIST,
                            getString(R.string.app_name),
                        ).build(),
                )
                isActive = true
            }
    }

    private fun observe(host: TtsPlaybackSessionHost) {
        if (observeJob?.isActive == true) return
        observeJob =
            serviceScope.launch {
                host.controller.state
                    .map(TtsPlaybackSessionModel::from)
                    // Speaking is republished for every word: the progress
                    // fraction and the highlighted passage both change. The
                    // surface here shows none of that, only play/pause, so
                    // without this the notification is rebuilt and re-posted
                    // several times a second on the main thread, each rebuild
                    // allocating four actions, five PendingIntents and four
                    // Icons for an identical result.
                    .distinctUntilChanged()
                    .takeWhile { model ->
                        if (!model.isActive) {
                            // Terminal: completion, explicit stop, or error —
                            // the queue is already cleared, so tear everything
                            // down exactly once and unsubscribe. A replacement
                            // session must enter through a new service start.
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        model.isActive
                    }.collect { model ->
                        publishPlaybackState(model)
                        startForeground(NOTIFICATION_ID, buildNotification(model))
                    }
            }
    }

    private fun publishPlaybackState(model: TtsPlaybackSessionModel) {
        val state =
            PlaybackState
                .Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                ).setState(
                    if (model.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                ).build()
        mediaSession?.setPlaybackState(state)
    }

    /** Builds the privacy-safe system transport with sentence-level previous and next actions. */
    private fun buildNotification(model: TtsPlaybackSessionModel): Notification {
        ensureChannel(this)
        val style =
            Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        mediaSession?.let { style.setMediaSession(it.sessionToken) }
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle(getString(R.string.tts_playback_notification_title))
            .setContentText(getString(R.string.tts_playback_notification_text))
            .setContentIntent(openAppIntent())
            .setDeleteIntent(actionIntent(ACTION_DISMISS))
            .setStyle(style)
            // The text is generic by design, so lock-screen transport controls
            // can show without exposing anything private.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(model.isPlaying)
            .setShowWhen(false)
            .addAction(
                action(
                    android.R.drawable.ic_media_previous,
                    R.string.tts_playback_action_previous_sentence,
                    ACTION_PREVIOUS_SENTENCE,
                ),
            ).addAction(
                if (model.isPlaying) {
                    action(android.R.drawable.ic_media_pause, R.string.tts_playback_action_pause, ACTION_PAUSE)
                } else {
                    action(android.R.drawable.ic_media_play, R.string.tts_playback_action_play, ACTION_PLAY)
                },
            ).addAction(
                action(
                    android.R.drawable.ic_media_next,
                    R.string.tts_playback_action_next_sentence,
                    ACTION_NEXT_SENTENCE,
                ),
            ).addAction(
                action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    R.string.tts_playback_action_stop,
                    ACTION_STOP,
                ),
            ).build()
    }

    private fun action(
        icon: Int,
        labelRes: Int,
        action: String,
    ): Notification.Action =
        Notification.Action
            .Builder(
                Icon.createWithResource(this, icon),
                getString(labelRes),
                actionIntent(action),
            ).build()

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, TtsPlaybackForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val SESSION_TAG = "whitenoise-read-aloud"
        private const val CHANNEL_ID = "read_aloud_playback"
        private const val NOTIFICATION_ID = 0x77A1
        internal const val ACTION_PLAY = "dev.ipf.whitenoise.android.tts.PLAY"
        internal const val ACTION_PAUSE = "dev.ipf.whitenoise.android.tts.PAUSE"
        internal const val ACTION_STOP = "dev.ipf.whitenoise.android.tts.STOP"
        internal const val ACTION_DISMISS = "dev.ipf.whitenoise.android.tts.DISMISS"
        internal const val ACTION_NEXT_SENTENCE = "dev.ipf.whitenoise.android.tts.NEXT_SENTENCE"
        internal const val ACTION_PREVIOUS_SENTENCE = "dev.ipf.whitenoise.android.tts.PREVIOUS_SENTENCE"

        /**
         * Test seam: resolves what the service mirrors. Production reaches the
         * process-wide app state through the Application.
         */
        internal var hostResolver: (Service) -> TtsPlaybackSessionHost? = { service ->
            (service.application as? WhiteNoiseApplication)?.appState?.let { appState ->
                object : TtsPlaybackSessionHost {
                    override val controller: TtsController get() = appState.ttsController

                    override fun nextSentence() {
                        appState.ttsHistorySession.nextSentence()
                    }

                    override fun previousSentence() {
                        appState.ttsHistorySession.previousSentence()
                    }

                    override fun stopSession() {
                        appState.stopSpeaking()
                    }
                }
            }
        }

        fun start(context: Context): Boolean =
            runCatching {
                context.startForegroundService(
                    Intent(context, TtsPlaybackForegroundService::class.java),
                )
                true
            }.getOrDefault(false)

        // Idempotence comes from the manager itself, not a process-static
        // flag: createNotificationChannel is a no-op for an existing id, and a
        // static flag would leak across application instances (tests included).
        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_read_aloud),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description =
                        context.getString(R.string.notification_channel_read_aloud_description)
                    setShowBadge(false)
                },
            )
        }
    }
}
