package dev.ipf.darkmatter.notifications

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted FCM token cache. The [MarmotFirebaseMessagingService] writes here
 * on every token rotation; [dev.ipf.darkmatter.state.DarkMatterAppState] reads
 * the last value when calling `upsertPushRegistration` so the registration
 * survives an app restart even before Firebase delivers a fresh
 * `onNewToken` callback.
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

    companion object {
        private const val PREFS_NAME = "darkmatter.push.tokens"
        private const val KEY_FCM_TOKEN = "fcm_token"

        fun create(context: Context): PushTokenStore =
            PushTokenStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
