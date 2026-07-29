package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global read-aloud speech rate. Null override means follow the OS rate from
 * the accessibility settings, so a user who already tuned speech there gets
 * that pace with zero configuration. Android platform preference — not White
 * Noise protocol data.
 */
class TtsRatePreferences(
    private val context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val _rateOverride = MutableStateFlow(readStoredOverride())
    val rateOverride: StateFlow<Float?> = _rateOverride.asStateFlow()

    fun setRateOverride(rate: Float?) {
        val normalized = rate?.takeIf { it > 0f }?.let(::nearestPreset)
        if (normalized == _rateOverride.value) return
        _rateOverride.value = normalized
        val edit = preferences.edit()
        if (normalized == null) {
            edit.remove(KEY_RATE_OVERRIDE)
        } else {
            edit.putFloat(KEY_RATE_OVERRIDE, normalized)
        }
        edit.apply()
    }

    /** The rate to hand to the engine: the override when set, else the OS rate. */
    fun resolvedRate(): Float = _rateOverride.value ?: systemRate()

    // Settings.Secure.TTS_DEFAULT_RATE is an integer percentage (100 == 1.0x).
    private fun systemRate(): Float =
        runCatching {
            Settings.Secure.getFloat(context.contentResolver, Settings.Secure.TTS_DEFAULT_RATE)
        }.getOrNull()
            ?.div(SYSTEM_RATE_SCALE)
            ?.takeIf { it > 0f }
            ?: DEFAULT_RATE

    // Stored values snap to the preset grid on read AND write: the settings
    // rows are the only selection UI, and an off-grid float (migration,
    // manual edit) would otherwise leave no row selected.
    private fun readStoredOverride(): Float? =
        try {
            preferences
                .getFloat(KEY_RATE_OVERRIDE, MISSING_RATE)
                .takeIf { it > 0f }
                ?.let(::nearestPreset)
        } catch (_: ClassCastException) {
            null
        }

    private fun nearestPreset(rate: Float): Float = PRESET_RATES.minBy { preset -> kotlin.math.abs(preset - rate) }

    companion object {
        // Presets instead of a free slider: TextToSpeech.setSpeechRate only
        // validates rate > 0 and every real clamp lives engine-side, so
        // engines disagree about what an extreme rate means.
        val PRESET_RATES = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
        const val DEFAULT_RATE = 1.0f

        private const val PREFERENCES_NAME = "whitenoise.tts_rate"
        private const val KEY_RATE_OVERRIDE = "rateOverride"
        private const val SYSTEM_RATE_SCALE = 100f
        private const val MISSING_RATE = -1f
    }
}
