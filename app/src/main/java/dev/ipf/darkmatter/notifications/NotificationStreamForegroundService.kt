package dev.ipf.darkmatter.notifications

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
import dev.ipf.darkmatter.BuildConfig
import dev.ipf.darkmatter.DarkMatterApplication
import dev.ipf.darkmatter.MainActivity
import dev.ipf.darkmatter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationStreamForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bootstrapJob: Job? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
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
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
                )
            }.onFailure {
                foregroundServiceDebug(it) { "startForeground rejected" }
            }.isSuccess
        when (
            decideForegroundStart(
                startForegroundSucceeded = startedForeground,
                bootstrapInFlight = bootstrapJob?.isActive == true,
            )
        ) {
            ForegroundStartDecision.RejectAndStop -> {
                // The enable path already optimistically persisted "background
                // connection on" and got a `true` from start() (the intent was
                // merely queued). Tell AppState the start actually failed so the
                // toggle doesn't lie and the user sees why. See #164.
                (application as? DarkMatterApplication)?.appState?.onBackgroundConnectionStartRejected()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ForegroundStartDecision.BootstrapAndKeep -> {
                bootstrapJob =
                    serviceScope.launch {
                        runCatching {
                            (application as DarkMatterApplication).appState.ensureNotificationRuntimeStarted()
                        }.onFailure {
                            foregroundServiceDebug(it) { "notification runtime failed" }
                        }
                    }
            }
            // Repeated onStartCommand calls (Android may redeliver) must not
            // stack notification-runtime bootstraps — an idempotency contract.
            ForegroundStartDecision.KeepRunningExistingBootstrap -> Unit
        }
        foregroundServiceDebug { "started" }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        foregroundServiceDebug { "destroyed" }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val ACTION_START = "dev.ipf.darkmatter.notifications.START_STREAM_FOREGROUND_SERVICE"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context): Boolean =
            runCatching {
                val appContext = context.applicationContext
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, NotificationStreamForegroundService::class.java).setAction(ACTION_START),
                )
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

/**
 * The pure decision about how [NotificationStreamForegroundService.onStartCommand] should proceed,
 * derived only from whether the foreground start succeeded and whether a bootstrap is already in
 * flight. Each value maps 1:1 to an action in onStartCommand; keeping this side-effect-free makes
 * the truth table testable without an Android context. See #164 and #158.
 */
internal sealed interface ForegroundStartDecision {
    /** start-foreground succeeded, no bootstrap in flight → launch a new bootstrap, START_STICKY. */
    data object BootstrapAndKeep : ForegroundStartDecision

    /** start-foreground succeeded, bootstrap already active → do nothing extra, START_STICKY. */
    data object KeepRunningExistingBootstrap : ForegroundStartDecision

    /** start-foreground rejected → notify AppState, stopSelf, START_NOT_STICKY. */
    data object RejectAndStop : ForegroundStartDecision
}

/**
 * Pure mapping from the two observable booleans to a [ForegroundStartDecision]. Extracted from
 * onStartCommand so the rejection-notification contract (#164) and the bootstrap-dedupe idempotency
 * contract are pinned by [ForegroundStartDecisionTest] and cannot silently regress in a refactor.
 */
internal fun decideForegroundStart(
    startForegroundSucceeded: Boolean,
    bootstrapInFlight: Boolean,
): ForegroundStartDecision =
    when {
        !startForegroundSucceeded -> ForegroundStartDecision.RejectAndStop
        bootstrapInFlight -> ForegroundStartDecision.KeepRunningExistingBootstrap
        else -> ForegroundStartDecision.BootstrapAndKeep
    }

private object BackgroundConnectionNotification {
    private const val CHANNEL_ID = "darkmatter.background_connection.v1"

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
            .setSmallIcon(R.drawable.ic_stat_darkmatter)
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
