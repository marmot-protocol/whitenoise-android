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
 * **Thread safety.** `FirebaseMessagingService.onNewToken` runs on a Firebase
 * background thread, not Main — so the old "every caller is on
 * `Dispatchers.Main.immediate`" confinement was false, and the
 * read-modify-write mutators below could lose updates or resurrect a
 * just-cleared token under concurrent sign-out. Every mutator (and the
 * read-modify-write read it depends on) now serializes through [lock], so the
 * store is safe to call from any thread. See #167.
 */
class PushTokenStore(
    private val preferences: SharedPreferences,
) {
    fun lastToken(): String? = preferences.getString(KEY_FCM_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setToken(token: String) {
        synchronized(LOCK) {
            preferences.edit().putString(KEY_FCM_TOKEN, token).apply()
        }
    }

    fun clear() {
        synchronized(LOCK) {
            preferences.edit().remove(KEY_FCM_TOKEN).apply()
        }
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
        synchronized(LOCK) {
            val current = pendingClears()
            if (account in current) return
            preferences.edit().putStringSet(KEY_PENDING_CLEARS, current + account).apply()
        }
    }

    fun clearPending(account: String) {
        if (account.isBlank()) return
        synchronized(LOCK) {
            val current = pendingClears()
            if (account !in current) return
            preferences.edit().putStringSet(KEY_PENDING_CLEARS, current - account).apply()
        }
    }

    /**
     * Account refs whose `setNativePushEnabled(false)` FFI call failed during
     * sign-out. The runtime flag is still enabled for them, so the sync loop
     * would otherwise re-register push; it must skip these and retry the
     * disable instead. Returns a defensive copy.
     */
    fun pendingDisables(): Set<String> = preferences.getStringSet(KEY_PENDING_DISABLES, emptySet())?.toSet() ?: emptySet()

    /** Mark [account] as needing a deferred `setNativePushEnabled(false)` retry. Idempotent. */
    fun recordPendingDisable(account: String) {
        if (account.isBlank()) return
        synchronized(LOCK) {
            val current = pendingDisables()
            if (account in current) return
            preferences.edit().putStringSet(KEY_PENDING_DISABLES, current + account).apply()
        }
    }

    fun clearPendingDisable(account: String) {
        if (account.isBlank()) return
        synchronized(LOCK) {
            val current = pendingDisables()
            if (account !in current) return
            preferences.edit().putStringSet(KEY_PENDING_DISABLES, current - account).apply()
        }
    }

    companion object {
        // Process-wide, NOT per-instance: callers construct fresh stores over
        // the same prefs file (onNewToken does PushTokenStore.create(...) on a
        // Firebase background thread while sign-out uses another instance), so
        // an instance lock would serialize nothing across them. See #167.
        private val LOCK = Any()
        private const val PREFS_NAME = "darkmatter.push.tokens"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_PENDING_CLEARS = "pending_clears"
        private const val KEY_PENDING_DISABLES = "pending_native_push_disables"

        fun create(context: Context): PushTokenStore =
            PushTokenStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
