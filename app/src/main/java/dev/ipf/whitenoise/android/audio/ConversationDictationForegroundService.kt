package dev.ipf.whitenoise.android.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
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
 * Owns the dictation lease inside White Noise's single foreground-service host.
 * Capture survives backgrounding and removal from recents. The notification is public
 * but deliberately contains no account, conversation, draft, or transcript
 * data. The controller remains the only owner of target and recognition state.
 */
internal class ConversationDictationForegroundService(
    private val service: NotificationStreamForegroundService,
) : ContextWrapper(service) {
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationObserver: Job? = null
    private var promotedController: ConversationDictationController? = null
    private var promotedSessionToken: String? = null
    private var foregroundPromoted = false

    val hasForegroundLease: Boolean
        get() = foregroundPromoted

    val foregroundServiceType: Int
        get() = if (foregroundPromoted) requireNotNull(promotedController).foregroundServiceType else 0

    /** Refreshes a remaining microphone lease after connection ownership changes. */
    fun refreshNotification() {
        val controller = promotedController ?: return
        val token = promotedSessionToken ?: return
        if (controller.hasDurableSession && controller.notificationSessionToken == token) {
            updateNotificationOrAbort(controller, token)
        } else {
            removeForegroundNotification()
        }
    }

    fun notificationOrNull(): Notification? =
        promotedController
            ?.takeIf {
                foregroundPromoted && it.hasDurableSession && it.notificationSessionToken == promotedSessionToken
            }?.let { controller -> buildConversationDictationNotification(this, controller, ::actionIntent) }

    private val conversationDictationDiagnostic: (String) -> Unit = { event ->
        val session = promotedSessionToken?.substringAfterLast(':')?.toLongOrNull() ?: 0L
        DictationDiagnostics.record("$event session=$session")
    }

    /** Routes session commands without re-posting controls that may complete synchronously. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Early returns reject stale ownership commands.
    fun onStartCommand(
        intent: Intent?,
        startId: Int,
    ): Int {
        val controller = hostResolver(service)?.conversationDictation
        val callbackSession = intent?.getStringExtra(EXTRA_SESSION_TOKEN)?.substringAfterLast(':')?.toLongOrNull() ?: 0L
        conversationDictationDiagnostic(
            "event=foreground_service_on_start has_controller=${controller != null} " +
                "durable=${controller?.hasDurableSession == true} callback_session=$callbackSession",
        )
        if (controller == null || !controller.hasDurableSession) {
            removeForegroundNotification()
            service.foreground.releaseDictation(startId)
        } else {
            val sessionToken = intent?.getStringExtra(EXTRA_SESSION_TOKEN)
            if (sessionToken == null || sessionToken != controller.notificationSessionToken) {
                // An orphan queued FGS start still needs to stop before Android's promotion deadline.
                // Never stop a service that currently authorizes a different live session.
                val owner = promotedController
                if (owner?.hasDurableSession != true || owner.notificationSessionToken != promotedSessionToken) {
                    service.foreground.releaseDictation(startId)
                }
                return Service.START_NOT_STICKY
            }
            if (isStaleCompletionCommand(intent, controller)) return Service.START_NOT_STICKY
            when (intent.action) {
                ACTION_CANCEL -> controller.cancel()
                ACTION_PASTE -> controller.paste()
                ACTION_SEND -> controller.send()
                ACTION_RETRY_AUDIO -> if (!controller.foregroundMicrophoneRequired) controller.retry()
                ACTION_DISCARD_RECOVERY -> controller.cancel()
            }
            // A completion received before the queued start must not promote stale controls
            // or open the microphone. Android posts foreground notifications asynchronously.
            if (!controller.hasDurableSession || controller.notificationSessionToken != sessionToken) {
                removeForegroundNotification()
                service.foreground.releaseDictation(startId)
                return Service.START_NOT_STICKY
            }
            ensureChannel(this)
            if (controller.state !is ConversationDictationState.Failed) {
                service.getSystemService(NotificationManager::class.java).cancel(
                    DICTATION_RECOVERY_NOTIFICATION_TAG,
                    DICTATION_RECOVERY_NOTIFICATION_ID,
                )
            }
            val alreadyPromoted =
                foregroundPromoted && promotedController === controller && promotedSessionToken == sessionToken
            if (alreadyPromoted || promoteOrCancel(controller, sessionToken, startId)) {
                // Promotion may synchronously cancel or replace the controller in tests or platform hooks.
                if (!controller.hasDurableSession || controller.notificationSessionToken != sessionToken) {
                    removeForegroundNotification()
                    service.foreground.releaseDictation(startId)
                    return Service.START_NOT_STICKY
                }
                publishPromotedController(controller, sessionToken, startId)
            }
        }
        return Service.START_NOT_STICKY
    }

    /** A ready callback can finish immediately; observe only its surviving promoted owner. */
    private fun publishPromotedController(
        controller: ConversationDictationController,
        sessionToken: String,
        startId: Int,
    ) {
        promotedController = controller
        promotedSessionToken = sessionToken
        activeService = this
        controller.onDurableServiceReady(sessionToken)
        if (controller.hasDurableSession && controller.notificationSessionToken == sessionToken) {
            observeNotification(controller, sessionToken)
        } else {
            removeForegroundNotification()
            service.foreground.releaseDictation(startId)
        }
    }

    /** Promotes an active capture or cancels it when Android rejects foreground microphone ownership. */
    @Suppress("TooGenericExceptionCaught")
    private fun promoteOrCancel(
        controller: ConversationDictationController,
        sessionToken: String,
        startId: Int,
    ): Boolean =
        try {
            foregroundPromoter(
                service,
                buildConversationDictationNotification(this, controller, ::actionIntent),
                controller.foregroundServiceType,
            )
            foregroundPromoted = true
            val callbackSession = sessionToken.substringAfterLast(':').toLongOrNull() ?: 0L
            conversationDictationDiagnostic("event=foreground_service_promoted callback_session=$callbackSession")
            true
        } catch (_: SecurityException) {
            conversationDictationDiagnostic("event=foreground_service_promotion_rejected type=SecurityException")
            controller.onDurableServiceStartFailed(sessionToken)
            service.foreground.releaseDictation(startId)
            false
        } catch (error: RuntimeException) {
            if (!error.isForegroundServiceStartRejection()) throw error
            conversationDictationDiagnostic(
                "event=foreground_service_promotion_rejected type=${error.javaClass.simpleName}",
            )
            controller.onDurableServiceStartFailed(sessionToken)
            service.foreground.releaseDictation(startId)
            false
        }

    /** Fails capture closed when Android removes the service that authorized background microphone use. */
    fun onDestroy() {
        notificationScope.cancel()
        conversationDictationDiagnostic("event=foreground_service_destroyed")
        promotedSessionToken?.let { token -> promotedController?.onDurableServiceDestroyed(token) }
        removeForegroundNotification()
    }

    /** Removes completed controls before Android asynchronously destroys the service. */
    private fun removeForegroundNotification() {
        notificationObserver?.cancel()
        notificationObserver = null
        foregroundPromoted = false
        promotedController = null
        promotedSessionToken = null
        if (activeService === this) activeService = null
        service.foreground.releaseDictation()
    }

    /** A completed controller must release the adapter owned by the current Android host. */
    internal fun releaseIfCompleted(): Boolean {
        if (promotedController?.hasDurableSession == true) return false
        removeForegroundNotification()
        return true
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
                    listOf(
                        controller.state,
                        controller.deliveryInProgress,
                        controller.completionActionsEnabled,
                        controller.foregroundMicrophoneRequired,
                        controller.notificationActionGeneration,
                        controller.foregroundRefreshRevision,
                    )
                }.collect {
                    if (controller.hasDurableSession && controller.notificationSessionToken == sessionToken) {
                        updateNotificationOrAbort(controller, sessionToken)
                    } else {
                        removeForegroundNotification()
                    }
                }
            }
    }

    /** Foreground permission can disappear while capture is already live. */
    @Suppress("TooGenericExceptionCaught") // Android's foreground-start rejection is a RuntimeException on API 31+.
    private fun updateNotificationOrAbort(
        controller: ConversationDictationController,
        sessionToken: String,
    ) {
        try {
            service.foreground.updateDictationNotification(
                buildConversationDictationNotification(this, controller, ::actionIntent),
            )
        } catch (_: SecurityException) {
            controller.onDurableServiceDestroyed(sessionToken)
            removeForegroundNotification()
        } catch (error: RuntimeException) {
            if (!error.isForegroundServiceStartRejection()) throw error
            controller.onDurableServiceDestroyed(sessionToken)
            removeForegroundNotification()
        }
    }

    /** Returns a stable PendingIntent for a notification action owned by this service. */
    internal fun actionIntent(
        action: String,
        sessionToken: String,
    ): PendingIntent {
        val actionGeneration =
            (promotedController ?: hostResolver(service)?.conversationDictation)
                ?.takeIf { it.notificationSessionToken == sessionToken }
                ?.notificationActionGeneration ?: 0L
        return PendingIntent.getService(
            this,
            action.hashCode(),
            Intent()
                .setClass(this, NotificationStreamForegroundService::class.java)
                .setAction(action)
                .setData(
                    Uri
                        .Builder()
                        .scheme("whitenoise-dictation")
                        .authority("session")
                        .appendPath(sessionToken)
                        .appendPath(action)
                        .appendPath(actionGeneration.toString())
                        .build(),
                ).putExtra(EXTRA_SESSION_TOKEN, sessionToken)
                .putExtra(EXTRA_ACTION_GENERATION, actionGeneration),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        internal const val CHANNEL_ID = "composer_dictation"

        @Volatile
        private var activeService: ConversationDictationForegroundService? = null

        /** Exposes the current microphone lease's presentation for lifecycle assertions. */
        internal fun activeNotificationOrNull(): Notification? =
            activeService?.let { service ->
                val controller = service.promotedController
                val token = service.promotedSessionToken
                controller
                    ?.takeIf { token != null && it.hasDurableSession && it.notificationSessionToken == token }
                    ?.let { controller ->
                        buildConversationDictationNotification(service, controller, service::actionIntent)
                    }
            }

        internal const val ACTION_CANCEL = "dev.ipf.whitenoise.android.dictation.CANCEL"
        internal const val ACTION_PASTE = "dev.ipf.whitenoise.android.dictation.PASTE"
        internal const val ACTION_SEND = "dev.ipf.whitenoise.android.dictation.SEND"
        internal const val ACTION_RETRY_AUDIO = "dev.ipf.whitenoise.android.dictation.RETRY_AUDIO"
        internal const val ACTION_DISCARD_RECOVERY = "dev.ipf.whitenoise.android.dictation.DISCARD_RECOVERY"
        internal const val EXTRA_SESSION_TOKEN = "dictation_session_token"
        internal const val EXTRA_ACTION_GENERATION = "dictation_action_generation"

        /** Completion actions belong to the controls and action generation that created them. */
        private fun isStaleCompletionCommand(intent: Intent, controller: ConversationDictationController): Boolean {
            val staleGeneration =
                intent.getLongExtra(EXTRA_ACTION_GENERATION, -1L) != controller.notificationActionGeneration
            return when (intent.action) {
                ACTION_CANCEL, ACTION_PASTE, ACTION_SEND ->
                    controller.state is ConversationDictationState.Failed || staleGeneration
                ACTION_RETRY_AUDIO, ACTION_DISCARD_RECOVERY -> !controller.recoveryHandedToComposer || staleGeneration
                else -> false
            }
        }

        /** A foreground service can run even when Android hides all of its drawer actions. */
        internal fun notificationControlsAvailable(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        /** Test seam for resolving the process-owned controller. */
        internal var hostResolver: (Service) -> ConversationDictationServiceHost? = { service ->
            (service.application as? WhiteNoiseApplication)
                ?.initializedAppState()
                ?.initializedConversationDictation()
                ?.let { controller ->
                    object : ConversationDictationServiceHost {
                        override val conversationDictation = controller
                    }
                }
        }

        /** Test seam for simulating platform rejection of foreground promotion. */
        internal var foregroundPromoter: (NotificationStreamForegroundService, Notification, Int) -> Unit =
            { service, notification, type -> service.foreground.promoteDictation(notification, type) }

        /** Starts the microphone service while the initiating composer is visible. */
        fun start(
            context: Context,
            sessionToken: String,
        ): Boolean =
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, NotificationStreamForegroundService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_SESSION_TOKEN, sessionToken),
                )
                true
            }.onFailure { error ->
                conversationDictationDiagnostic(
                    "event=foreground_service_enqueue_failed type=${error.javaClass.simpleName}",
                )
            }.getOrDefault(false)

        /** Releases only dictation; connection work keeps the shared host alive. */
        @Suppress("UNUSED_PARAMETER") // Existing controller API accepts its application context.
        fun stop(context: Context) {
            val accepted = NotificationStreamForegroundService.releaseCompletedDictation()
            conversationDictationDiagnostic(
                "event=foreground_service_stop accepted=$accepted",
            )
        }

        internal const val ACTION_START = "dev.ipf.whitenoise.android.dictation.START"

        internal fun isCommand(intent: Intent?): Boolean =
            intent?.action in
                setOf(
                    ACTION_START,
                    ACTION_CANCEL,
                    ACTION_PASTE,
                    ACTION_SEND,
                    ACTION_RETRY_AUDIO,
                    ACTION_DISCARD_RECOVERY,
                ) ||
                intent?.hasExtra(EXTRA_SESSION_TOKEN) == true

        /** Creates the low-importance, badge-free channel once per installation. */
        internal fun ensureChannel(context: Context) {
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
internal fun Throwable.isForegroundServiceStartRejection(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && this is ForegroundServiceStartNotAllowedException
