package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class NotificationTapTokens(
    private val preferences: SharedPreferences,
    private val randomBytes: (ByteArray) -> Unit = secureRandom::nextBytes,
) {
    fun tokenFor(notificationKey: String): String {
        val key = storageKey(notificationKey)
        preferences.getString(key, null)?.takeIf { isPlausibleToken(it) }?.let { return it }
        val token = newToken()
        preferences.edit().putString(key, token).apply()
        return token
    }

    fun isValid(
        notificationKey: String,
        token: String?,
    ): Boolean {
        val expected = preferences.getString(storageKey(notificationKey), null) ?: return false
        return expected == token?.takeIf { isPlausibleToken(it) }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        randomBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
    }

    companion object {
        private const val PREFERENCES_NAME = "notification_tap_tokens"
        private const val TOKEN_BYTES = 24

        private val secureRandom = SecureRandom()

        fun create(context: Context): NotificationTapTokens =
            NotificationTapTokens(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )

        internal fun storageKey(notificationKey: String): String = "tap_" + sha256Hex(notificationKey)

        internal fun isPlausibleToken(token: String): Boolean = token.length >= 16

        private fun sha256Hex(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
    }
}
