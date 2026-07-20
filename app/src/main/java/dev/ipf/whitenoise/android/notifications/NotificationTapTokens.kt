package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class NotificationTapTokens(
    private val preferences: SharedPreferences,
    private val randomBytes: (ByteArray) -> Unit = secureRandom::nextBytes,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun tokenFor(notificationKey: String): String =
        synchronized(tokenMutationLock) {
            val key = storageKey(notificationKey)
            val timeKey = storageTimeKey(notificationKey)
            preferences.getString(key, null)?.takeIf { isPlausibleToken(it) }?.let {
                preferences.edit().putLong(timeKey, nowMillis()).apply()
                return@synchronized it
            }
            val token = newToken()
            preferences
                .edit()
                .putString(key, token)
                .putLong(timeKey, nowMillis())
                .apply()
            pruneIfNeeded()
            token
        }

    fun remove(notificationKey: String) {
        synchronized(tokenMutationLock) {
            preferences
                .edit()
                .remove(storageKey(notificationKey))
                .remove(storageTimeKey(notificationKey))
                .apply()
        }
    }

    fun isValid(
        notificationKey: String,
        token: String?,
    ): Boolean {
        val expected =
            preferences
                .getString(storageKey(notificationKey), null)
                ?.takeIf(::isPlausibleToken)
                ?: return false
        val candidate = token?.takeIf { isPlausibleToken(it) } ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8),
        )
    }

    private fun pruneIfNeeded() {
        val tokenKeys =
            preferences.all.keys
                .filter(::isTokenStorageKey)
                .map { key -> key to preferences.getLong(timeStorageKeyForTokenStorageKey(key), Long.MIN_VALUE) }
                .sortedBy { (_, lastUsed) -> lastUsed }
        val overflow = tokenKeys.size - MAX_STORED_TOKENS
        if (overflow <= 0) return
        val editor = preferences.edit()
        tokenKeys.take(overflow).forEach { (key, _) ->
            editor.remove(key).remove(timeStorageKeyForTokenStorageKey(key))
        }
        editor.apply()
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        randomBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val PREFERENCES_NAME = "notification_tap_tokens"
        private const val TOKEN_KEY_PREFIX = "tap_"
        private const val TOKEN_TIME_KEY_PREFIX = "tap_time_"
        private const val TOKEN_BYTES = 24
        private const val TOKEN_ENCODED_LENGTH = 32
        internal const val MAX_STORED_TOKENS = 512

        private val secureRandom = SecureRandom()
        private val tokenMutationLock = Any()

        fun create(context: Context): NotificationTapTokens =
            NotificationTapTokens(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )

        internal fun storageKey(notificationKey: String): String = TOKEN_KEY_PREFIX + sha256Hex(notificationKey)

        internal fun storageTimeKey(notificationKey: String): String = TOKEN_TIME_KEY_PREFIX + sha256Hex(notificationKey)

        internal fun isPlausibleToken(token: String): Boolean =
            token.length == TOKEN_ENCODED_LENGTH &&
                token.all { char ->
                    char in 'a'..'z' ||
                        char in 'A'..'Z' ||
                        char in '0'..'9' ||
                        char == '-' ||
                        char == '_'
                }

        private fun isTokenStorageKey(key: String): Boolean = key.startsWith(TOKEN_KEY_PREFIX) && !key.startsWith(TOKEN_TIME_KEY_PREFIX)

        private fun timeStorageKeyForTokenStorageKey(key: String): String = TOKEN_TIME_KEY_PREFIX + key.removePrefix(TOKEN_KEY_PREFIX)

        private fun sha256Hex(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
    }
}
