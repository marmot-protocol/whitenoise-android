package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-selected TTS engine override. Null means follow the OS default.
 * Android platform preference — not White Noise protocol data.
 */
class TtsEnginePreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val _selectedEnginePackage = MutableStateFlow(preferences.getString(KEY_SELECTED_ENGINE, null))
    val selectedEnginePackage: StateFlow<String?> = _selectedEnginePackage.asStateFlow()

    fun selectedEngine(): String? = _selectedEnginePackage.value?.trim()?.takeIf { it.isNotEmpty() }

    fun setSelectedEngine(enginePackage: String?) {
        val normalized = enginePackage?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == _selectedEnginePackage.value) return
        _selectedEnginePackage.value = normalized
        val edit = preferences.edit()
        if (normalized == null) {
            edit.remove(KEY_SELECTED_ENGINE)
        } else {
            edit.putString(KEY_SELECTED_ENGINE, normalized)
        }
        edit.apply()
    }

    internal companion object {
        const val PREFERENCES_NAME = "whitenoise.tts_engine"
        private const val KEY_SELECTED_ENGINE = "selectedEnginePackage"
    }
}
