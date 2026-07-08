package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_NAME = "whitenoise.push.tokens"
private const val SECURE_PREFS_NAME = "whitenoise.push.tokens.secure"

// Plaintext prefs used only when the keystore-backed store can't be opened on
// this device. Distinct file so it never aliases the encrypted store's bytes.
private const val FALLBACK_PREFS_NAME = "whitenoise.push.tokens.fallback"
private const val KEY_FCM_TOKEN = "fcm_token"
private const val KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC = "pending_native_push_registration_sync"
private const val KEY_PENDING_PUSH_WAKE_CATCH_UP = "pending_push_wake_catch_up"
private const val KEY_PENDING_CLEARS = "pending_clears"
private const val KEY_PENDING_DISABLES = "pending_native_push_disables"

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
     * True when a MIP-05 push wake arrived but Android rejected the foreground
     * stream start before the notification runtime could fetch/drain it. The
     * next runtime-start, connectivity, or foreground catch-up trigger retries
     * the fetch instead of losing that wake. See #1160.
     */
    fun pushWakeCatchUpPending(): Boolean = preferences.getBoolean(KEY_PENDING_PUSH_WAKE_CATCH_UP, false)

    // commit() (not apply()) so the #1160 retry marker is durable before the
    // Firebase background service returns and the process can be killed.
    @SuppressLint("ApplySharedPref")
    fun recordPendingPushWakeCatchUp() {
        synchronized(LOCK) {
            preferences.edit().putBoolean(KEY_PENDING_PUSH_WAKE_CATCH_UP, true).commit()
        }
    }

    fun clearPendingPushWakeCatchUp() {
        synchronized(LOCK) {
            preferences.edit().remove(KEY_PENDING_PUSH_WAKE_CATCH_UP).apply()
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

    @Suppress("DEPRECATION")
    companion object {
        // Process-wide, NOT per-instance: callers construct fresh stores over
        // the same prefs file (onNewToken does PushTokenStore.create(...) on a
        // Firebase background thread while sign-out uses another instance), so
        // an instance lock would serialize nothing across them. See #167.
        private val LOCK = Any()

        fun create(context: Context): PushTokenStore {
            val appContext = context.applicationContext
            val secure = openSecure(appContext)
            val legacy = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Migration reads/writes the (possibly encrypted) store, which can
            // throw on a corrupted value; a migration failure just defers the
            // copy to a later launch (legacy is left intact), so never let it
            // crash construction.
            runCatching { migrateLegacyPushTokenPreferences(legacy, secure) }
            return PushTokenStore(secure)
        }

        private fun openSecure(context: Context): SharedPreferences =
            try {
                createSecure(context)
            } catch (primary: Exception) {
                // A GeneralSecurityException/IOException is usually a corrupted
                // store that one delete-and-recreate clears. But keystore-level
                // faults — MasterKey build failing, a missing/broken
                // AndroidKeyStore provider on some OEM/old/rooted devices, or
                // master-key invalidation — throw again on recreate. create()
                // runs in an AppState field initializer, so an uncaught throw
                // here crashes app launch (the old plaintext prefs never threw,
                // so this would be a regression). Degrade to plaintext prefs
                // (pre-encryption behavior, the LOW risk this store accepted
                // before) rather than taking the app down.
                runCatching { recreateAfterCorruption(context) }
                    .getOrElse { context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE) }
            }

        private fun recreateAfterCorruption(context: Context): SharedPreferences {
            context.deleteSharedPreferences(SECURE_PREFS_NAME)
            return createSecure(context)
        }

        private fun createSecure(context: Context): SharedPreferences {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}

internal fun migrateLegacyPushTokenPreferences(
    legacy: SharedPreferences,
    secure: SharedPreferences,
) {
    val legacyValues = legacy.all
    if (legacyValues.isEmpty()) return
    val editor = secure.edit()
    var wrote = false
    copyStringIfMissing(legacyValues, secure, editor, KEY_FCM_TOKEN).also { wrote = wrote || it }
    copyBooleanIfMissing(legacyValues, secure, editor, KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC).also { wrote = wrote || it }
    copyBooleanIfMissing(legacyValues, secure, editor, KEY_PENDING_PUSH_WAKE_CATCH_UP).also { wrote = wrote || it }
    copyStringSetIfMissing(legacyValues, secure, editor, KEY_PENDING_CLEARS).also { wrote = wrote || it }
    copyStringSetIfMissing(legacyValues, secure, editor, KEY_PENDING_DISABLES).also { wrote = wrote || it }
    if (!wrote || editor.commit()) {
        legacy.edit().clear().apply()
    }
}

private fun copyStringIfMissing(
    values: Map<String, *>,
    secure: SharedPreferences,
    editor: SharedPreferences.Editor,
    key: String,
): Boolean {
    if (secure.contains(key)) return false
    val value = values[key] as? String ?: return false
    editor.putString(key, value)
    return true
}

private fun copyBooleanIfMissing(
    values: Map<String, *>,
    secure: SharedPreferences,
    editor: SharedPreferences.Editor,
    key: String,
): Boolean {
    if (secure.contains(key)) return false
    val value = values[key] as? Boolean ?: return false
    editor.putBoolean(key, value)
    return true
}

private fun copyStringSetIfMissing(
    values: Map<String, *>,
    secure: SharedPreferences,
    editor: SharedPreferences.Editor,
    key: String,
): Boolean {
    if (secure.contains(key)) return false
    @Suppress("UNCHECKED_CAST")
    val value = values[key] as? Set<String> ?: return false
    editor.putStringSet(key, value)
    return true
}
