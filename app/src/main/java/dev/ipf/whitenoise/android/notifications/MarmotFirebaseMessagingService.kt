package dev.ipf.whitenoise.android.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.WhiteNoiseApplication

/**
 * Receives MIP-05 silent wake pushes and FCM token rotations.
 *
 * Push payloads are intentionally empty — the server just nudges the device
 * to wake up and let Marmot pull the encrypted event from the relay over
 * the existing transport. We never decrypt anything in this service; we just
 * trigger the foreground stream so the local-notification pipeline can fetch
 * and present whatever's pending.
 *
 * Token rotation is persisted to [PushTokenStore] and forwarded to the app's
 * runtime so it can re-call `upsertPushRegistration` against the MIP-05
 * server with the new value.
 */
class MarmotFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        fcmDebug { "FCM token rotated (${token.length} chars)" }
        val app = applicationContext as? WhiteNoiseApplication
        when (decidePushTokenRotation(appRuntimeReachable = app != null)) {
            // AppState.onPushTokenRotated persists the token AND schedules a
            // native-push re-sync atomically, so route the whole rotation through it.
            PushTokenRotationDispatch.ToAppRuntime -> app!!.appState.onPushTokenRotated(token)
            // Fallback for when applicationContext can't resolve to
            // WhiteNoiseApplication (early process init, restricted context,
            // teardown): persist the token *and* nudge the runtime to
            // re-register. #755 — persisting alone left the MIP-05 server on a
            // stale token until a later foreground sync. Starting the
            // foreground stream service bootstraps AppState off this Firebase
            // background thread; that bootstrap runs
            // syncNativePushRegistrationIfEnabled() so the rotated token reaches
            // the push server.
            PushTokenRotationDispatch.PersistAndScheduleReRegistration -> {
                PushTokenStore.create(applicationContext).setToken(token)
                val scheduled = NotificationStreamForegroundService.syncNativePushRegistration(applicationContext)
                if (!scheduled) {
                    fcmDebug { "Native-push registration sync start rejected; durable retry recorded" }
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // MIP-05 wake pushes carry no display notification. If a payload
        // ever sneaks one in we still drop the server-supplied text (the
        // local pipeline owns notification text), but the wake itself
        // must always happen — an early return here would cause a missed
        // foreground-stream resync for the rest of the payload.
        if (message.notification != null) {
            fcmDebug { "Ignoring notification body on MIP-05 push" }
        }
        // A wake only reaches us because native push is registered, and native
        // push is independent of the "Keep connected" toggle — gating the wake
        // on the background-connection preference would silently drop every
        // fetch for a user who runs native push without a persistent
        // connection (the whole point of native push).
        fcmDebug { "MIP-05 wake push received; starting foreground stream" }
        wakeForegroundStream()
    }

    private fun wakeForegroundStream() {
        try {
            val started = NotificationStreamForegroundService.start(applicationContext, ForegroundStartTrigger.PushWake)
            if (!started) {
                Log.w(TAG, "Failed to start foreground stream from push wake")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to start foreground stream from push wake", error)
        }
    }

    companion object {
        private const val TAG = "MarmotFcmService"
    }
}

/**
 * How [MarmotFirebaseMessagingService.onNewToken] should handle a rotated FCM
 * token, derived only from whether the app runtime ([WhiteNoiseApplication]) is
 * reachable from `applicationContext`. Each value couples token persistence
 * with scheduling native push re-registration so the two can never diverge.
 * Keeping this side-effect-free makes the #755 invariant testable without an
 * Android context. See [decidePushTokenRotation].
 */
internal sealed interface PushTokenRotationDispatch {
    /** True iff this dispatch persists the rotated token (every variant must). */
    val persistsToken: Boolean

    /** True iff this dispatch schedules native push re-registration (every variant must). */
    val schedulesReRegistration: Boolean

    /** Runtime reachable → AppState.onPushTokenRotated persists + schedules atomically. */
    data object ToAppRuntime : PushTokenRotationDispatch {
        override val persistsToken = true
        override val schedulesReRegistration = true
    }

    /**
     * Runtime unreachable → persist the token locally AND start the foreground
     * stream service, whose bootstrap re-syncs native push. #755: the old
     * fallback persisted only, stranding the MIP-05 server on a stale token.
     */
    data object PersistAndScheduleReRegistration : PushTokenRotationDispatch {
        override val persistsToken = true
        override val schedulesReRegistration = true
    }
}

/**
 * Pure mapping from "is the app runtime reachable?" to a
 * [PushTokenRotationDispatch]. Extracted from [MarmotFirebaseMessagingService.onNewToken]
 * so the #755 contract — token persistence is always coupled with scheduling
 * re-registration — is pinned by [dev.ipf.whitenoise.android.notifications.PushTokenRotationDispatchTest]
 * and cannot silently regress to a persist-only fallback.
 */
internal fun decidePushTokenRotation(appRuntimeReachable: Boolean): PushTokenRotationDispatch =
    if (appRuntimeReachable) {
        PushTokenRotationDispatch.ToAppRuntime
    } else {
        PushTokenRotationDispatch.PersistAndScheduleReRegistration
    }

private inline fun fcmDebug(message: () -> String) {
    // Debug-only so operational push logs don't ship in release logcat. See #39.
    if (BuildConfig.DEBUG) Log.d("MarmotFcmService", message())
}
