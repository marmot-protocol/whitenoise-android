package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val QUIET_MEDIA_VOLUME = 0.35f
private const val MEDIUM_MEDIA_VOLUME = 0.60f
private const val LOUD_MEDIA_VOLUME = 0.85f

/** Local-only read-aloud preferences for mixing speech over active media. */
class TtsMediaMixPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<TtsMediaMixState> = _state.asStateFlow()

    /** Enables media mixing without changing the saved volume preset. */
    fun setEnabled(enabled: Boolean) {
        if (_state.value.enabled == enabled) return
        _state.value = _state.value.copy(enabled = enabled)
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Selects one of the bounded framework volume presets. */
    fun setVolume(volume: TtsMediaMixVolume) {
        if (_state.value.volume == volume) return
        _state.value = _state.value.copy(volume = volume)
        preferences.edit().putString(KEY_VOLUME, volume.name).apply()
    }

    /** Restores only named presets, falling back safely when old data is invalid. */
    private fun readState(): TtsMediaMixState =
        TtsMediaMixState(
            enabled = runCatching { preferences.getBoolean(KEY_ENABLED, false) }.getOrDefault(false),
            volume =
                runCatching { preferences.getString(KEY_VOLUME, null) }
                    .getOrNull()
                    ?.let { stored -> TtsMediaMixVolume.entries.firstOrNull { it.name == stored } }
                    ?: TtsMediaMixVolume.MEDIUM,
        )

    internal companion object {
        internal const val PREFERENCES_NAME = "whitenoise.tts_media_mix"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_VOLUME = "volume"
    }
}

/** Observable media-mix settings consumed by the process-wide controller. */
data class TtsMediaMixState(
    val enabled: Boolean,
    val volume: TtsMediaMixVolume,
)

/** Accessible presets kept inside Android's documented `[0, 1]` volume range. */
enum class TtsMediaMixVolume(
    val frameworkVolume: Float,
) {
    QUIET(QUIET_MEDIA_VOLUME),
    MEDIUM(MEDIUM_MEDIA_VOLUME),
    LOUD(LOUD_MEDIA_VOLUME),
}
