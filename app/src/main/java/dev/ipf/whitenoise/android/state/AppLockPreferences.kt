package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Secure process-restart state for the optional app-open lock (#406).
 *
 * The value is only a timestamp, but it controls whether chat content is shown
 * before an OS credential challenge, so keep it in the same Keystore-backed
 * preference store class used for drafts rather than the plain app prefs.
 */
@Suppress("DEPRECATION")
internal object AppLockPreferences {
    private const val SECURE_FILE = "whitenoise.app_lock.secure"
    private const val LAST_UNLOCKED_AT_KEY = "last_unlocked_at_millis"

    // One instance for the process: every create() pays a Keystore access plus
    // a Tink keyset init and a synchronous prefs-file read, and the call sites
    // run on the main thread (cold start, every unlock, every backgrounding).
    // Concurrent instances over the same file are also a known instability of
    // the library.
    @Volatile
    private var cachedSecure: SharedPreferences? = null
    private val cacheLock = Any()

    fun readLastUnlockedAtMillis(context: Context): Long {
        // One immediate retry after invalidating the cache: a Keystore entry
        // invalidated mid-process recreates through the corruption-recovery
        // path right away instead of failing every call until the next one.
        repeat(2) {
            runCatching {
                return openSecure(context.applicationContext).getLong(LAST_UNLOCKED_AT_KEY, 0L)
            }.onFailure { cachedSecure = null }
        }
        return 0L
    }

    fun writeLastUnlockedAtMillis(
        context: Context,
        value: Long,
    ) {
        repeat(2) {
            runCatching {
                openSecure(context.applicationContext)
                    .edit()
                    .putLong(LAST_UNLOCKED_AT_KEY, value.coerceAtLeast(0L))
                    .apply()
                return
            }.onFailure { cachedSecure = null }
        }
    }

    private fun openSecure(context: Context): SharedPreferences {
        cachedSecure?.let { return it }
        return synchronized(cacheLock) {
            cachedSecure ?: run {
                val created =
                    try {
                        create(context)
                    } catch (error: GeneralSecurityException) {
                        recreateAfterCorruption(context)
                    } catch (error: IOException) {
                        recreateAfterCorruption(context)
                    }
                cachedSecure = created
                created
            }
        }
    }

    private fun recreateAfterCorruption(context: Context): SharedPreferences {
        context.deleteSharedPreferences(SECURE_FILE)
        return create(context)
    }

    private fun create(context: Context): SharedPreferences {
        val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
