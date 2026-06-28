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
        if (app != null) {
            app.appState.onPushTokenRotated(token)
        } else {
            PushTokenStore.create(applicationContext).setToken(token)
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

private inline fun fcmDebug(message: () -> String) {
    // Debug-only so operational push logs don't ship in release logcat. See #39.
    if (BuildConfig.DEBUG) Log.d("MarmotFcmService", message())
}
