package dev.ipf.whitenoise.android.state

import android.content.Context
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Secure process-restart state for the optional app-open lock (#406).
 *
 * The value is only a timestamp, but it controls whether chat content is shown
 * before an OS credential challenge, so it stays in a Keystore-backed store
 * rather than the plain app prefs.
 */
internal object AppLockPreferences {
    private const val SECURE_FILE = "whitenoise.app_lock.keystore"
    private const val LEGACY_SECURE_FILE = "whitenoise.app_lock.secure"
    private const val KEY_ALIAS = "whitenoise.app_lock.aes_gcm.v1"
    private const val LAST_UNLOCKED_AT_KEY = "last_unlocked_at_millis"

    // One instance for the process: every open pays a Keystore access plus a
    // synchronous prefs-file read, and the call sites run on the main thread
    // (cold start, every unlock, every backgrounding).
    @Volatile
    private var cachedStore: KeystoreSecureStore? = null
    private val cacheLock = Any()

    fun readLastUnlockedAtMillis(context: Context): Long = readLastUnlockedAtMillis(context, secureStore = null)

    internal fun readLastUnlockedAtMillis(
        context: Context,
        secureStore: KeystoreSecureStore?,
        legacyReader: (Context, String) -> Map<String, String>? = LegacySecurePreferences::read,
    ): Long {
        // One immediate retry after invalidating the cache: a Keystore entry
        // invalidated mid-process recreates through the corruption-recovery
        // path right away instead of failing every call until the next one.
        repeat(2) {
            runCatching {
                return resolvedStore(context.applicationContext, secureStore, legacyReader)
                    .readAll()[LAST_UNLOCKED_AT_KEY]
                    ?.toLongOrNull()
                    ?: 0L
            }.onFailure {
                if (secureStore != null) {
                    runCatching { secureStore.clear() }
                } else {
                    recover()
                }
            }
        }
        return 0L
    }

    fun writeLastUnlockedAtMillis(
        context: Context,
        value: Long,
    ) = writeLastUnlockedAtMillis(context, value, secureStore = null)

    internal fun writeLastUnlockedAtMillis(
        context: Context,
        value: Long,
        secureStore: KeystoreSecureStore?,
        legacyReader: (Context, String) -> Map<String, String>? = LegacySecurePreferences::read,
    ) {
        repeat(2) {
            runCatching {
                resolvedStore(context.applicationContext, secureStore, legacyReader)
                    .write(LAST_UNLOCKED_AT_KEY, value.coerceAtLeast(0L).toString())
                return
            }.onFailure {
                if (secureStore != null) {
                    runCatching { secureStore.clear() }
                } else {
                    recover()
                }
            }
        }
    }

    private fun resolvedStore(
        context: Context,
        secureStore: KeystoreSecureStore?,
        legacyReader: (Context, String) -> Map<String, String>?,
    ): KeystoreSecureStore {
        if (secureStore != null) {
            importLegacyStore(context, secureStore, legacyReader)
            return secureStore
        }
        return store(context, legacyReader)
    }

    private fun store(
        context: Context,
        legacyReader: (Context, String) -> Map<String, String>? = LegacySecurePreferences::read,
    ): KeystoreSecureStore {
        cachedStore?.let { return it }
        return synchronized(cacheLock) {
            cachedStore ?: create(context).also {
                importLegacyStore(context, it, legacyReader)
                cachedStore = it
            }
        }
    }

    // A failed read/write means the keyset or the payload is unusable. The
    // stored value is a disposable timestamp, so clearing it is safe: the next
    // launch simply asks for a credential challenge once.
    private fun recover() {
        runCatching { cachedStore?.clear() }
        cachedStore = null
    }

    private fun create(context: Context): KeystoreSecureStore =
        KeystoreSecureStore(
            context = context,
            fileName = SECURE_FILE,
            keyProvider = AndroidKeystoreSecretKeyProvider(KEY_ALIAS),
        )

    /**
     * One-way import from the retired `androidx.security-crypto` file, so an
     * existing install is not challenged for credentials again purely because
     * the storage backend changed. The legacy file is deleted afterwards; a
     * failure to read it is not worth surfacing for a timestamp.
     */
    private fun importLegacyStore(
        context: Context,
        target: KeystoreSecureStore,
        legacyReader: (Context, String) -> Map<String, String>?,
    ) {
        val imported =
            try {
                legacyReader(context, LEGACY_SECURE_FILE)?.get(LAST_UNLOCKED_AT_KEY)
            } catch (error: GeneralSecurityException) {
                context.deleteSharedPreferences(LEGACY_SECURE_FILE)
                null
            } catch (error: IOException) {
                context.deleteSharedPreferences(LEGACY_SECURE_FILE)
                null
            }
        val stored =
            if (imported == null) {
                null
            } else {
                try {
                    target.readAll()
                } catch (error: GeneralSecurityException) {
                    null
                }
            }
        when {
            imported == null -> Unit
            // Unreadable new store: retry on a later open rather than dropping
            // the only copy of the timestamp.
            stored == null -> Unit
            // A value already here wins; re-importing would overwrite a fresher
            // unlock with the stale legacy one on every recovery.
            stored.containsKey(LAST_UNLOCKED_AT_KEY) ->
                context.deleteSharedPreferences(LEGACY_SECURE_FILE)
            persistImported(target, imported) ->
                context.deleteSharedPreferences(LEGACY_SECURE_FILE)
            else -> Unit
        }
    }

    private fun persistImported(
        target: KeystoreSecureStore,
        value: String,
    ): Boolean =
        try {
            target.putAllDurably(mapOf(LAST_UNLOCKED_AT_KEY to value))
        } catch (error: GeneralSecurityException) {
            false
        }
}
