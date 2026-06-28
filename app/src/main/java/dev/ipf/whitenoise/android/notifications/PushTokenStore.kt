package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted FCM token cache. The [MarmotFirebaseMessagingService] writes here
 * on every token rotation; [dev.ipf.whitenoise.android.state.WhiteNoiseAppState] reads
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
     * True when a rotated token was persisted while the app runtime could not
     * be reached and the best-effort foreground-service nudge has not yet been
     * confirmed by a native-push registration sync. This is intentionally
     * process-durable: if Android rejects the service start, the next AppState
     * sync trigger can still drain the work instead of silently falling back to
     * "persist only". See #755.
     */
    fun nativePushRegistrationSyncPending(): Boolean = preferences.getBoolean(KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC, false)

    // commit() (not apply()) so the #755 retry marker is durable before the
    // token-rotation fallback starts a foreground service that may itself be
    // killed/rejected — an async apply() could lose the flag on process death.
    @SuppressLint("ApplySharedPref")
    fun recordPendingNativePushRegistrationSync() {
        synchronized(LOCK) {
            preferences.edit().putBoolean(KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC, true).commit()
        }
    }

    fun clearPendingNativePushRegistrationSync() {
        synchronized(LOCK) {
            preferences.edit().remove(KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC).apply()
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

    // Accounts whose sign-out `setNativePushEnabled(false)` failed: the sync skips them and retries the disable.
    fun pendingDisables(): Set<String> = preferences.getStringSet(KEY_PENDING_DISABLES, emptySet())?.toSet() ?: emptySet()

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
        private const val PREFS_NAME = "whitenoise.push.tokens"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC =
            "pending_native_push_registration_sync"
        private const val KEY_PENDING_CLEARS = "pending_clears"
        private const val KEY_PENDING_DISABLES = "pending_native_push_disables"

        fun create(context: Context): PushTokenStore =
            PushTokenStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
