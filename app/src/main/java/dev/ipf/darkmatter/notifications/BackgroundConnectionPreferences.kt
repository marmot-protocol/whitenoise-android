package dev.ipf.darkmatter.notifications

import android.content.Context

object BackgroundConnectionPreferences {
    private const val PREFERENCES_NAME = "darkmatter"
    private const val KEY_ENABLED = "background_connection_enabled"

    fun isEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
