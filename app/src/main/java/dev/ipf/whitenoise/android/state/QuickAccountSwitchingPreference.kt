package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Owns only the app-wide opt-in; account activation and native eligibility remain independent. */
internal class QuickAccountSwitchingPreference(
    private val preferences: SharedPreferences,
) {
    /** Reads the same off-by-default key once per AppState, retaining Compose observation on explicit updates. */
    var enabled by mutableStateOf(preferences.getBoolean(QUICK_ACCOUNT_SWITCHING_KEY, false))
        private set

    /** Stores an explicit user choice immediately without selecting an account or modifying native state. */
    fun update(enabled: Boolean) {
        this.enabled = enabled
        preferences.edit().putBoolean(QUICK_ACCOUNT_SWITCHING_KEY, enabled).apply()
    }
}

/** App-wide opt-in survives account changes/sign-out; a fresh owner reads false after preferences are erased. */
internal val WhiteNoiseAppState.quickAccountSwitching: Boolean
    get() = quickAccountSwitchingPreference.enabled

/** Persists the Appearance choice through the AppState-owned preference holder. */
internal fun WhiteNoiseAppState.updateQuickAccountSwitching(enabled: Boolean) {
    quickAccountSwitchingPreference.update(enabled)
}
