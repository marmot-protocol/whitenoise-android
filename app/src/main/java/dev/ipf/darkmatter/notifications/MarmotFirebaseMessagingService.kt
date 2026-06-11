package dev.ipf.darkmatter.notifications

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.ipf.darkmatter.DarkMatterApplication

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
        Log.d(TAG, "FCM token rotated (${token.length} chars)")
        PushTokenStore.create(applicationContext).setToken(token)
        val app = applicationContext as? DarkMatterApplication ?: return
        app.appState.onPushTokenRotated(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // MIP-05 wake pushes carry no display notification — if a payload
        // ever sneaks one in, ignore it. We don't trust server-side text;
        // the local pipeline owns notification text.
        if (message.notification != null) {
            Log.d(TAG, "Ignoring notification body on MIP-05 push")
            return
        }
        Log.d(TAG, "MIP-05 wake push received; starting foreground stream")
        wakeForegroundStream()
    }

    private fun wakeForegroundStream() {
        try {
            val intent = Intent(applicationContext, NotificationStreamForegroundService::class.java)
            ContextCompat.startForegroundService(applicationContext, intent)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to start foreground stream from push wake", error)
        }
    }

    companion object {
        private const val TAG = "MarmotFcmService"
    }
}
