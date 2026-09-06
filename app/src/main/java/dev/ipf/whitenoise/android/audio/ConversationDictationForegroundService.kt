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
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationObserver: Job? = null
    private var promotedController: ConversationDictationController? = null
    private var promotedSessionToken: String? = null

    /** Dictation is command-only and never exposes a bound service interface. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Promotes capture before routing metadata-free Cancel/Paste/Send actions to the process owner. */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val controller = hostResolver(this)?.conversationDictation
        conversationDictationDiagnostic(
            "event=foreground_service_on_start has_controller=${controller != null} " +
                "durable=${controller?.hasDurableSession == true}",
        )
        if (controller == null || !controller.hasDurableSession) {
            stopSelfResult(startId)
        } else {
            val sessionToken = intent?.getStringExtra(EXTRA_SESSION_TOKEN)
            if (sessionToken == null || sessionToken != controller.notificationSessionToken) {
                // An orphan queued FGS start still needs to stop before Android's promotion deadline.
                // Never stop a service that currently authorizes a different live session.
                val owner = promotedController
                if (owner?.hasDurableSession != true || owner.notificationSessionToken != promotedSessionToken) {
                    stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }
            ensureChannel(this)
            if (promoteOrCancel(controller, sessionToken, startId)) {
                // Promotion may synchronously cancel or replace the controller in tests or platform hooks.
                if (!controller.hasDurableSession || controller.notificationSessionToken != sessionToken) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                when (intent.action) {
                    ACTION_CANCEL -> controller.cancel()
                    ACTION_PASTE -> controller.paste()
                    ACTION_SEND -> controller.send()
                }
                // A completion action received during startup must not briefly open the microphone.
                if (!controller.hasDurableSession || controller.notificationSessionToken != sessionToken) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                promotedController = controller
                promotedSessionToken = sessionToken
                controller.onDurableServiceReady(sessionToken)
                if (controller.hasDurableSession && controller.notificationSessionToken == sessionToken) {
                    observeNotification(controller, sessionToken)
                } else {
                    promotedController = null
                    promotedSessionToken = null
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Promotes an active capture or cancels it when Android rejects foreground microphone ownership. */
    @Suppress("TooGenericExceptionCaught")
    private fun promoteOrCancel(
        controller: ConversationDictationController,
        sessionToken: String,
        startId: Int,
    ): Boolean =
        try {
            foregroundPromoter(this, buildNotification(controller))
            conversationDictationDiagnostic("event=foreground_service_promoted")
            true
        } catch (_: SecurityException) {
            conversationDictationDiagnostic("event=foreground_service_promotion_rejected type=SecurityException")
            cancelRejectedPromotion(controller, sessionToken, startId)
            false
        } catch (error: RuntimeException) {
            if (!error.isForegroundServiceStartRejection()) throw error
            conversationDictationDiagnostic(
                "event=foreground_service_promotion_rejected type=${error.javaClass.simpleName}",
            )
            cancelRejectedPromotion(controller, sessionToken, startId)
            false
        }

    /** Releases controller ownership and stops this service after foreground promotion is rejected. */
    private fun cancelRejectedPromotion(
        controller: ConversationDictationController,
        sessionToken: String,
        startId: Int,
    ) {
        controller.onDurableServiceStartFailed(sessionToken)
        stopSelfResult(startId)
    }

    /** Fails capture closed when Android removes the service that authorized background microphone use. */
    override fun onDestroy() {
        notificationScope.cancel()
        conversationDictationDiagnostic("event=foreground_service_destroyed")
        promotedSessionToken?.let { token -> promotedController?.onDurableServiceDestroyed(token) }
        promotedController = null
        promotedSessionToken = null
        super.onDestroy()
    }

    /** Builds a public but metadata-free notification with the only actions valid off-screen. */
    private fun buildNotification(controller: ConversationDictationController): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle(getString(R.string.dictation_notification_title))
            .setContentText(getString(notificationStatus(controller)))
            .setProgress(
                0,
                0,
                controller.state is ConversationDictationState.Starting ||
                    controller.state is ConversationDictationState.Processing,
            ).setContentIntent(openAppIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    R.string.cancel,
                    ACTION_CANCEL,
                    requireNotNull(controller.notificationSessionToken),
                    !controller.deliveryInProgress,
                ),
            ).addAction(
                action(
                    android.R.drawable.ic_menu_edit,
                    R.string.paste,
                    ACTION_PASTE,
                    requireNotNull(controller.notificationSessionToken),
                    controller.completionActionsEnabled,
                ),
            ).addAction(
                action(
                    android.R.drawable.ic_menu_send,
                    R.string.send,
                    ACTION_SEND,
                    requireNotNull(controller.notificationSessionToken),
                    controller.completionActionsEnabled,
                ),
            ).build()

    /** Describes actual readiness/finalization, never a model download or invented percentage. */
    private fun notificationStatus(controller: ConversationDictationController): Int =
        when {
            controller.deliveryInProgress -> R.string.message_status_pending
            controller.state is ConversationDictationState.Starting -> R.string.dictation_starting
            controller.state is ConversationDictationState.Processing -> R.string.dictation_processing
            else -> R.string.dictation_notification_text
        }

    /** Keeps system controls truthful when capture becomes finalization or an irrevocable dispatch. */
    private fun observeNotification(
        controller: ConversationDictationController,
        sessionToken: String,
    ) {
        notificationObserver?.cancel()
        notificationObserver =
            notificationScope.launch {
                snapshotFlow {
                    Triple(controller.state, controller.deliveryInProgress, controller.completionActionsEnabled)
                }.collect {
                    if (controller.hasDurableSession && controller.notificationSessionToken == sessionToken) {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(controller))
                    }
                }
            }
    }

    /** Creates one immutable foreground-service action without embedding conversation data. */
    private fun action(
        icon: Int,
        labelRes: Int,
        action: String,
        sessionToken: String,
        enabled: Boolean,
    ): Notification.Action =
        Notification.Action
            .Builder(
                Icon.createWithResource(this, icon),
                getString(labelRes),
                if (enabled) actionIntent(action, sessionToken) else null,
            ).build()

    /** Returns a stable PendingIntent for a notification action owned by this service. */
    private fun actionIntent(
        action: String,
        sessionToken: String,
    ): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent()
                .setClass(this, ConversationDictationForegroundService::class.java)
                .setAction(action)
                .setData(
                    Uri
                        .Builder()
                        .scheme("whitenoise-dictation")
                        .authority("session")
                        .appendPath(sessionToken)
                        .appendPath(action)
                        .build(),
                ).putExtra(EXTRA_SESSION_TOKEN, sessionToken),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Reopens the existing White Noise task without exposing the dictation target in intent extras. */
    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent()
                .setClass(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        internal const val CHANNEL_ID = "composer_dictation"
        private const val NOTIFICATION_ID = 0x77D1
        internal const val ACTION_CANCEL = "dev.ipf.whitenoise.android.dictation.CANCEL"
        internal const val ACTION_PASTE = "dev.ipf.whitenoise.android.dictation.PASTE"
        internal const val ACTION_SEND = "dev.ipf.whitenoise.android.dictation.SEND"
        internal const val EXTRA_SESSION_TOKEN = "dictation_session_token"

        /** A foreground service can run even when Android hides all of its drawer actions. */
        internal fun notificationControlsAvailable(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }

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
        fun start(
            context: Context,
            sessionToken: String,
        ): Boolean =
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ConversationDictationForegroundService::class.java)
                        .putExtra(EXTRA_SESSION_TOKEN, sessionToken),
                )
                true
            }.onFailure { error ->
                conversationDictationDiagnostic(
                    "event=foreground_service_enqueue_failed type=${error.javaClass.simpleName}",
                )
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
