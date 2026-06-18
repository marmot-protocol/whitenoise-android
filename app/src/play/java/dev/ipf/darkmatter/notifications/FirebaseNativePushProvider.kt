package dev.ipf.darkmatter.notifications

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * `play`-flavor factory for the active [NativePushProvider]. Resolved from the
 * shared [NativePush] accessor; only the `play` source set compiles this file,
 * so the unqualified call there binds here.
 */
fun nativePushProvider(): NativePushProvider = FirebaseNativePushProvider

/**
 * Firebase Cloud Messaging implementation of [NativePushProvider], used by the
 * Play Store build. All Firebase / Google Play Services symbols are confined to
 * this `play`-flavor source set so the `zapstore` build can drop the SDKs
 * entirely.
 */
object FirebaseNativePushProvider : NativePushProvider {
    private const val TAG = "DMFcmPush"

    /**
     * True only if (1) Google Play Services is available on the device AND
     * (2) the Firebase app has actually been initialized at process start.
     * Without (2), `FirebaseMessaging.getInstance()` throws
     * `IllegalStateException` deep in the FCM SDK; this gate keeps that
     * exception out of the foreground / account-switch / token-rotation paths
     * that would otherwise crash the process.
     */
    override fun isPlatformAvailable(context: Context): Boolean {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (status != ConnectionResult.SUCCESS) return false
        return FirebaseApp.getApps(context).isNotEmpty()
    }

    override suspend fun fetchToken(context: Context): String? {
        if (!isPlatformAvailable(context)) return null
        return runCatching {
            suspendCancellableCoroutine<String?> { continuation ->
                // The Firebase Task API has no cancel surface, so the
                // completion listener can fire after this coroutine is
                // cancelled. Guard the resume on isActive so a stale callback
                // doesn't try to push a value onto a closed continuation; the
                // task completes in the background and its result is dropped.
                // The outer runCatching is a belt — `getInstance()` itself can
                // throw IllegalStateException if FirebaseApp isn't initialized,
                // and we'd rather drop the token fetch than crash.
                FirebaseMessaging
                    .getInstance()
                    .token
                    .addOnCompleteListener { task ->
                        if (continuation.isActive) {
                            continuation.resume(if (task.isSuccessful) task.result else null)
                        }
                    }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.e(TAG, "FCM token fetch failed", error)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
