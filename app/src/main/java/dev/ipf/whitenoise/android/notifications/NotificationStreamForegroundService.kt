package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val ACTION_START = "dev.ipf.whitenoise.android.notifications.START_STREAM_FOREGROUND_SERVICE"
private const val ACTION_SYNC_NATIVE_PUSH_REGISTRATION =
    "dev.ipf.whitenoise.android.notifications.SYNC_NATIVE_PUSH_REGISTRATION"
private const val EXTRA_START_TRIGGER = "dev.ipf.whitenoise.android.notifications.EXTRA_START_TRIGGER"
private const val START_TRIGGER_USER_TOGGLE = "user_toggle"
private const val START_TRIGGER_PUSH_WAKE = "push_wake"
private const val START_TRIGGER_SYSTEM_WAKE = "system_wake"

class NotificationStreamForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bootstrapJob: Job? = null
    private var pendingNativePushRegistrationSync = false

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val trigger = foregroundStartTrigger(intent)
        // Android 14+ rejects a foreground-service start from a disallowed
        // context with ForegroundServiceStartNotAllowedException (an
        // IllegalStateException); a missing FGS grant throws SecurityException.
        // Either one is fatal if it propagates out of onStartCommand. Bail
        // cleanly instead — the next foregroundable trigger (app open, FCM
        // high-priority wake) will retry. See #164.
        val startedForeground =
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    BackgroundConnectionNotification.build(this),
                    foregroundServiceTypeForTrigger(trigger),
                )
            }.onFailure {
                foregroundServiceDebug(it) { "startForeground rejected" }
            }.isSuccess
        val syncNativePushRegistration = shouldSyncNativePushRegistration(intent?.action)
        if (syncNativePushRegistration) pendingNativePushRegistrationSync = true
        when (
            decideForegroundStart(
                startForegroundSucceeded = startedForeground,
                bootstrapInFlight = bootstrapJob?.isActive == true,
                syncNativePushRegistrationRequested = syncNativePushRegistration,
            )
        ) {
            ForegroundStartDecision.RejectBackgroundConnectionStartAndStop -> {
                // The enable path already optimistically persisted "background
                // connection on" and got a `true` from start() (the intent was
                // merely queued). Tell AppState the start actually failed so the
                // toggle doesn't lie and the user sees why. Push/boot wakes are
                // not user-initiated toggles, so a rejected wake must not
                // permanently disable the user's "Keep connected" preference.
                // See #164 and #777.
                if (shouldReconcileBackgroundConnectionRejection(trigger)) {
                    (application as? WhiteNoiseApplication)?.appState?.onBackgroundConnectionStartRejected()
                }
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ForegroundStartDecision.RejectNativePushSyncAndStop -> {
                // A one-shot native-push sync nudge is not a Keep Connected
                // request. The durable pending flag was recorded before the
                // start intent was queued, so stop without flipping the user's
                // background-connection preference or showing the #164 toast.
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ForegroundStartDecision.BootstrapAndKeep -> {
                bootstrapJob =
                    serviceScope.launch {
                        val stopAfterSync =
                            shouldStopAfterOneShotForegroundStart(
                                oneShotRequested = isOneShotForegroundStart(syncNativePushRegistration, trigger),
                                backgroundConnectionEnabled = BackgroundConnectionPreferences.isEnabled(applicationContext),
                            )
                        runCatching {
                            val appState = (application as WhiteNoiseApplication).appState
                            appState.ensureNotificationRuntimeStarted()
                            drainPendingNativePushRegistrationSync(appState)
                        }.onFailure {
                            foregroundServiceDebug(it) { "notification runtime failed" }
                        }
                        if (stopAfterSync) stopSelf(startId)
                    }
            }
            // Repeated onStartCommand calls (Android may redeliver) must not
            // stack notification-runtime bootstraps — an idempotency contract.
            ForegroundStartDecision.KeepRunningExistingBootstrap -> {
                val oneShotRequested = isOneShotForegroundStart(syncNativePushRegistration, trigger)
                if (oneShotRequested) {
                    val inFlightBootstrap = bootstrapJob
                    val stopAfterSync =
                        shouldStopAfterOneShotForegroundStart(
                            oneShotRequested = oneShotRequested,
                            backgroundConnectionEnabled = BackgroundConnectionPreferences.isEnabled(applicationContext),
                        )
                    serviceScope.launch {
                        runCatching {
                            inFlightBootstrap?.join()
                            if (syncNativePushRegistration) {
                                drainPendingNativePushRegistrationSync((application as WhiteNoiseApplication).appState)
                            }
                        }.onFailure {
                            foregroundServiceDebug(it) { "notification runtime one-shot completion failed" }
                        }
                        // A one-shot sync nudge that raced an existing bootstrap must not
                        // keep the foreground service (and its notification) alive unless
                        // the user enabled Keep Connected — same rule as BootstrapAndKeep.
                        if (stopAfterSync) stopSelf(startId)
                    }
                }
            }
        }
        foregroundServiceDebug { "started" }
        return START_STICKY
    }

    private suspend fun drainPendingNativePushRegistrationSync(appState: WhiteNoiseAppState) {
        val store = PushTokenStore.create(applicationContext)
        if (!pendingNativePushRegistrationSync && !store.nativePushRegistrationSyncPending()) return
        pendingNativePushRegistrationSync = false
        // The FCM token-rotation fallback (#755) starts this service to
        // re-register native push when it can't reach AppState directly.
        // ensureNotificationRuntimeStarted() alone does not push the rotated
        // token to the MIP-05 server, so sync explicitly here. Idempotent: a
        // no-op when the token/server/relay fingerprint is unchanged.
        appState.syncNativePushRegistrationIfEnabled()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app from recents removes the task but, because this
        // foreground service keeps the process alive, the singleton AppState
        // (and its in-memory foreground / visible-conversation suppression
        // state) survives. Activity onStop is not guaranteed on this path, so
        // reset that state here; otherwise a chat that was on screen at
        // swipe-away keeps silencing its own notifications (issue #821).
        (application as? WhiteNoiseApplication)?.appState?.onTaskRemoved()
        foregroundServiceDebug { "task removed" }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        foregroundServiceDebug { "destroyed" }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val NOTIFICATION_ID = 1001

        internal fun start(
            context: Context,
            trigger: ForegroundStartTrigger = ForegroundStartTrigger.UserToggle,
        ): Boolean =
            startForegroundServiceSafely(context) { appContext ->
                Intent(appContext, NotificationStreamForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_START_TRIGGER, trigger.extraValue)
            }

        // #755 fallback: record the durable pending-sync flag, then nudge this
        // service to re-register native push. Carries no start-trigger extra, so
        // foregroundStartTrigger() classifies it as a SystemWake and a rejected
        // start never disables the user's Keep Connected preference.
        fun syncNativePushRegistration(context: Context): Boolean {
            val appContext = context.applicationContext
            PushTokenStore.create(appContext).recordPendingNativePushRegistrationSync()
            return startForegroundServiceSafely(appContext) { ctx ->
                Intent(ctx, NotificationStreamForegroundService::class.java)
                    .setAction(ACTION_SYNC_NATIVE_PUSH_REGISTRATION)
            }
        }

        private fun startForegroundServiceSafely(
            context: Context,
            buildIntent: (Context) -> Intent,
        ): Boolean =
            runCatching {
                val appContext = context.applicationContext
                ContextCompat.startForegroundService(appContext, buildIntent(appContext))
                true
            }.getOrElse {
                foregroundServiceDebug(it) { "start rejected" }
                false
            }

        fun stop(context: Context): Boolean =
            runCatching {
                val appContext = context.applicationContext
                appContext.stopService(
                    Intent(appContext, NotificationStreamForegroundService::class.java),
                )
            }.getOrElse {
                foregroundServiceDebug(it) { "stop rejected" }
                false
            }
    }
}

internal enum class ForegroundStartTrigger(
    val extraValue: String,
) {
    UserToggle(START_TRIGGER_USER_TOGGLE),
    PushWake(START_TRIGGER_PUSH_WAKE),
    SystemWake(START_TRIGGER_SYSTEM_WAKE),
}

/** True only for a one-shot native-push registration sync start (#755). */
internal fun shouldSyncNativePushRegistration(action: String?): Boolean = action == ACTION_SYNC_NATIVE_PUSH_REGISTRATION

/**
 * A token-rotation fallback should not turn a one-shot re-registration nudge
 * into a persistent background connection notification unless the user already
 * enabled keep-connected. Existing stream starts keep their prior behavior.
 */
internal fun shouldStopAfterNativePushRegistrationSync(
    syncRequested: Boolean,
    backgroundConnectionEnabled: Boolean,
): Boolean =
    shouldStopAfterOneShotForegroundStart(
        oneShotRequested = syncRequested,
        backgroundConnectionEnabled = backgroundConnectionEnabled,
    )

internal fun shouldStopAfterOneShotForegroundStart(
    oneShotRequested: Boolean,
    backgroundConnectionEnabled: Boolean,
): Boolean = oneShotRequested && !backgroundConnectionEnabled

internal fun isOneShotForegroundStart(
    syncNativePushRegistrationRequested: Boolean,
    trigger: ForegroundStartTrigger,
): Boolean = syncNativePushRegistrationRequested || trigger == ForegroundStartTrigger.PushWake

/**
 * The pure decision about how [NotificationStreamForegroundService.onStartCommand] should proceed,
 * derived only from whether the foreground start succeeded, whether a bootstrap
 * is already in flight, and whether this intent is a one-shot native-push sync.
 * Each value maps 1:1 to an action in onStartCommand; keeping this side-effect-free
 * makes the truth table testable without an Android context. See #164 and #158.
 */
internal sealed interface ForegroundStartDecision {
    /** start-foreground succeeded, no bootstrap in flight → launch a new bootstrap, START_STICKY. */
    data object BootstrapAndKeep : ForegroundStartDecision

    /** start-foreground succeeded, bootstrap already active → do nothing extra, START_STICKY. */
    data object KeepRunningExistingBootstrap : ForegroundStartDecision

    /** Background-connection foreground start rejected → notify AppState, stopSelf, START_NOT_STICKY. */
    data object RejectBackgroundConnectionStartAndStop : ForegroundStartDecision

    /** One-shot native-push sync foreground start rejected → stopSelf only, START_NOT_STICKY. */
    data object RejectNativePushSyncAndStop : ForegroundStartDecision
}

/**
 * Pure mapping from the observable start state to a [ForegroundStartDecision].
 * Extracted from onStartCommand so the rejection-notification contract (#164),
 * the sync-only rejection contract (#755), and the bootstrap-dedupe idempotency
 * contract are pinned by [ForegroundStartDecisionTest] and cannot silently
 * regress in a refactor.
 */
internal fun decideForegroundStart(
    startForegroundSucceeded: Boolean,
    bootstrapInFlight: Boolean,
    syncNativePushRegistrationRequested: Boolean,
): ForegroundStartDecision =
    when {
        !startForegroundSucceeded && syncNativePushRegistrationRequested ->
            ForegroundStartDecision.RejectNativePushSyncAndStop
        !startForegroundSucceeded -> ForegroundStartDecision.RejectBackgroundConnectionStartAndStop
        bootstrapInFlight -> ForegroundStartDecision.KeepRunningExistingBootstrap
        else -> ForegroundStartDecision.BootstrapAndKeep
    }

internal fun shouldReconcileBackgroundConnectionRejection(trigger: ForegroundStartTrigger): Boolean = trigger == ForegroundStartTrigger.UserToggle

internal fun foregroundServiceTypeForTrigger(trigger: ForegroundStartTrigger): Int =
    when (trigger) {
        // Android 14+ forbids BOOT_COMPLETED / MY_PACKAGE_REPLACED starts for
        // remoteMessaging foreground services. The user-enabled persistent
        // encrypted-message connection still needs to come back after those
        // system wakes, so only that path uses the manifest-declared specialUse
        // type; user and FCM starts keep the narrower remoteMessaging type.
        ForegroundStartTrigger.SystemWake -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        ForegroundStartTrigger.UserToggle,
        ForegroundStartTrigger.PushWake,
        -> ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
    }

private fun foregroundStartTrigger(intent: Intent?): ForegroundStartTrigger {
    val raw = intent?.getStringExtra(EXTRA_START_TRIGGER)
    return when (raw) {
        START_TRIGGER_USER_TOGGLE -> ForegroundStartTrigger.UserToggle
        START_TRIGGER_PUSH_WAKE -> ForegroundStartTrigger.PushWake
        START_TRIGGER_SYSTEM_WAKE -> ForegroundStartTrigger.SystemWake
        // Older ACTION_START intents were the explicit user-toggle/app path.
        null -> if (intent?.action == ACTION_START) ForegroundStartTrigger.UserToggle else ForegroundStartTrigger.SystemWake
        else -> ForegroundStartTrigger.SystemWake
    }
}

private object BackgroundConnectionNotification {
    private const val CHANNEL_ID = "whitenoise.background_connection.v1"

    @Volatile
    private var channelEnsured = false

    fun build(context: Context): Notification {
        ensureChannel(context)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val text = context.getString(R.string.background_connection_notification_text)
        val hint = context.getString(R.string.background_connection_notification_hint)
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle(context.getString(R.string.background_connection_notification_title))
            .setContentText(text)
            // Collapsed view stays the compact one-liner; expanding reveals the
            // hint teaching users they can long-press to hide this ongoing
            // notification without killing the connection (per-channel disable
            // is allowed on a LOW-importance channel).
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$hint"))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (channelEnsured) return
        synchronized(this) {
            if (channelEnsured) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_background_connection),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_background_connection_description)
                    setShowBadge(false)
                }
            manager.createNotificationChannel(channel)
            channelEnsured = true
        }
    }
}

private inline fun foregroundServiceDebug(message: () -> String) {
    // Debug-only so operational INFO logs don't ship in release logcat. See #39.
    if (BuildConfig.DEBUG) Log.i("DMForegroundSvc", message())
}

private inline fun foregroundServiceDebug(
    error: Throwable,
    message: () -> String,
) {
    Log.e("DMForegroundSvc", message(), error)
}
