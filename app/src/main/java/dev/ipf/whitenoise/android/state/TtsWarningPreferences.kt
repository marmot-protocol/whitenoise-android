package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-engine acknowledgement for the Unknown TTS trust warning. Android
 * platform preference — not White Noise protocol data.
 */
class TtsWarningPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    fun hasAcknowledged(enginePackage: String): Boolean {
        val pkg = enginePackage.trim()
        if (pkg.isEmpty()) return false
        return preferences.getStringSet(KEY_ACKNOWLEDGED_PACKAGES, emptySet()).orEmpty().contains(pkg)
    }

    fun acknowledge(enginePackage: String) {
        val pkg = enginePackage.trim()
        if (pkg.isEmpty()) return
        val updated = preferences.getStringSet(KEY_ACKNOWLEDGED_PACKAGES, emptySet()).orEmpty() + pkg
        preferences.edit().putStringSet(KEY_ACKNOWLEDGED_PACKAGES, HashSet(updated)).apply()
    }

    internal companion object {
        const val PREFERENCES_NAME = "whitenoise.tts_warning"
        private const val KEY_ACKNOWLEDGED_PACKAGES = "acknowledgedEnginePackages"
    }
}
