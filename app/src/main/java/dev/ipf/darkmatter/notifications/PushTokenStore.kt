package dev.ipf.darkmatter.notifications

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted FCM token cache. The [MarmotFirebaseMessagingService] writes here
 * on every token rotation; [dev.ipf.darkmatter.state.DarkMatterAppState] reads
 * the last value when calling `upsertPushRegistration` so the registration
 * survives an app restart even before Firebase delivers a fresh
 * `onNewToken` callback.
 *
 * **Thread confinement.** The non-suspending mutators (`setToken`,
 * `recordPendingClear`, `clearPending`) do a read-modify-write on
 * SharedPreferences without an internal lock. They are safe today because
 * every caller is on `Dispatchers.Main.immediate` — either via
 * [dev.ipf.darkmatter.state.DarkMatterAppState]'s `notificationScope` or
 * the Firebase service callback that resolves on the same dispatcher. A
 * new off-main caller must serialize through one of those scopes (or add
 * an internal lock here) to avoid lost-update on the pending-clears set.
 */
class PushTokenStore(
    private val preferences: SharedPreferences,
) {
    fun lastToken(): String? = preferences.getString(KEY_FCM_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setToken(token: String) {
        preferences.edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_FCM_TOKEN).apply()
    }

    /**
     * Account refs whose `clearPushRegistration` FFI call previously failed.
     * The next [syncNativePushRegistrationIfEnabled]-style drain should retry
     * them; sign-out / disable that succeeded never enters this set.
     *
     * Returns a defensive copy — `SharedPreferences.getStringSet` may share
     * its backing instance, and mutating that is undefined behavior.
     */
    fun pendingClears(): Set<String> = preferences.getStringSet(KEY_PENDING_CLEARS, emptySet())?.toSet() ?: emptySet()

    /**
     * Mark [account] as needing a deferred `clearPushRegistration` retry.
     * Idempotent — re-recording an already-pending ref is a no-op.
     */
    fun recordPendingClear(account: String) {
        if (account.isBlank()) return
        val current = pendingClears()
        if (account in current) return
        preferences.edit().putStringSet(KEY_PENDING_CLEARS, current + account).apply()
    }

    fun clearPending(account: String) {
        if (account.isBlank()) return
        val current = pendingClears()
        if (account !in current) return
        preferences.edit().putStringSet(KEY_PENDING_CLEARS, current - account).apply()
    }

    companion object {
        private const val PREFS_NAME = "darkmatter.push.tokens"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_PENDING_CLEARS = "pending_clears"

        fun create(context: Context): PushTokenStore =
            PushTokenStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
