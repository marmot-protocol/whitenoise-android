package dev.ipf.whitenoise.android.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication

/** Process-level owner exposed to the microphone foreground service. */
internal interface ConversationDictationServiceHost {
    val conversationDictation: ConversationDictationController
}

/**
 * Keeps an explicitly started composer-dictation session alive while White
 * Noise is backgrounded or removed from recents. The notification is public
 * but deliberately contains no account, conversation, draft, or transcript
 * data. The controller remains the only owner of target and recognition state.
 */
class ConversationDictationForegroundService : Service() {
    /** Dictation is command-only and never exposes a bound service interface. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Promotes capture before routing generic Done/Cancel notification actions to the process owner. */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val controller = hostResolver(this)?.conversationDictation
        if (controller == null || !controller.hasDurableSession) {
            stopSelf()
        } else {
            ensureChannel(this)
            if (promoteOrCancel(controller)) {
                when (intent?.action) {
                    ACTION_DONE -> controller.stop()
                    ACTION_CANCEL -> controller.cancel()
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Promotes an active capture or cancels it when Android rejects foreground microphone ownership. */
    @Suppress("TooGenericExceptionCaught")
    private fun promoteOrCancel(controller: ConversationDictationController): Boolean =
        try {
            foregroundPromoter(this, buildNotification())
            true
        } catch (_: SecurityException) {
            cancelRejectedPromotion(controller)
            false
        } catch (error: RuntimeException) {
            if (!error.isForegroundServiceStartRejection()) throw error
            cancelRejectedPromotion(controller)
            false
        }

    /** Releases controller ownership and stops this service after foreground promotion is rejected. */
    private fun cancelRejectedPromotion(controller: ConversationDictationController) {
        controller.cancel()
        stopSelf()
    }

    /** Deliberately preserves explicit capture when the user removes the UI task from recents. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Product contract: a recents swipe leaves explicit dictation running.
        // Done and Cancel remain available in the foreground notification.
        super.onTaskRemoved(rootIntent)
    }

    /** Fails capture closed when Android removes the service that authorized background microphone use. */
    override fun onDestroy() {
        hostResolver(this)
            ?.conversationDictation
            ?.takeIf { it.hasDurableSession }
            ?.onDurableServiceDestroyed()
        super.onDestroy()
    }

    /** Builds a public but metadata-free notification with the only actions valid off-screen. */
    private fun buildNotification(): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle(getString(R.string.dictation_notification_title))
            .setContentText(getString(R.string.dictation_notification_text))
            .setContentIntent(openAppIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(action(android.R.drawable.ic_media_pause, R.string.dictation_done, ACTION_DONE))
            .addAction(
                action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    R.string.dictation_cancel,
                    ACTION_CANCEL,
                ),
            ).build()

    /** Creates one immutable foreground-service action without embedding conversation data. */
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

    /** Returns a stable PendingIntent for a notification action owned by this service. */
    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, ConversationDictationForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Reopens the existing White Noise task without exposing the dictation target in intent extras. */
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
        private const val CHANNEL_ID = "composer_dictation"
        private const val NOTIFICATION_ID = 0x77D1
        internal const val ACTION_DONE = "dev.ipf.whitenoise.android.dictation.DONE"
        internal const val ACTION_CANCEL = "dev.ipf.whitenoise.android.dictation.CANCEL"

        /** Test seam for resolving the process-owned controller. */
        internal var hostResolver: (Service) -> ConversationDictationServiceHost? = { service ->
            (service.application as? WhiteNoiseApplication)?.appState?.let { appState ->
                object : ConversationDictationServiceHost {
                    override val conversationDictation: ConversationDictationController
                        get() = appState.conversationDictation
                }
            }
        }

        /** Test seam for simulating platform rejection of foreground promotion. */
        internal var foregroundPromoter: (ConversationDictationForegroundService, Notification) -> Unit =
            { service, notification ->
                ServiceCompat.startForeground(
                    service,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            }

        /** Starts the microphone service while the initiating composer is visible. */
        fun start(context: Context): Boolean =
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ConversationDictationForegroundService::class.java),
                )
                true
            }.getOrDefault(false)

        /** Stops the service after the controller has released recognition ownership. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ConversationDictationForegroundService::class.java))
            }
        }

        /** Creates the low-importance, badge-free channel once per installation. */
        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_dictation),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_dictation_description)
                    setShowBadge(false)
                },
            )
        }
    }
}

/** Recognizes API 31+'s explicit foreground-start rejection without resolving that class on older Android. */
private fun Throwable.isForegroundServiceStartRejection(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && this is ForegroundServiceStartNotAllowedException
