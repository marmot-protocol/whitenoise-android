package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import dev.ipf.whitenoise.android.audio.tts.TtsAudioFocusOwner
import dev.ipf.whitenoise.android.audio.tts.TtsController
import dev.ipf.whitenoise.android.audio.tts.TtsTimingStore

/** Wires process-wide TTS policy to local preferences and Android media state. */
internal fun createAppTtsController(
    context: Context,
    ratePreferences: TtsRatePreferences,
    mediaMixPreferences: TtsMediaMixPreferences,
): TtsController =
    TtsController(
        audioFocus = TtsAudioFocusOwner(context),
        speechRate = { ratePreferences.resolvedRate() },
        mediaMixEnabled = { mediaMixPreferences.state.value.enabled },
        mediaMixVolume = { mediaMixPreferences.state.value.volume.frameworkVolume },
        isMediaPlaybackActive = {
            context.applicationContext.getSystemService(AudioManager::class.java)?.isMusicActive == true
        },
        timingStore = TtsTimingPreferences(context),
    )

/**
 * Persisted per-engine timing capabilities: whether the engine reports word
 * ranges, and the measured pace of its voice. Android platform preference —
 * engine capability, not White Noise protocol data.
 *
 * Keys embed the engine package so one engine's verdict can never be credited
 * to another: that would permanently disable the estimated word highlight on a
 * range-silent engine, or pace it against a voice it was never calibrated for.
 */
class TtsTimingPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) : TtsTimingStore {
    override fun rangeVerdict(engineKey: String): Boolean? {
        val key = rangeVerdictKey(engineKey)
        val known = engineKey.isNotEmpty() && preferences.contains(key)
        return if (known) preferences.getBoolean(key, false) else null
    }

    override fun setRangeVerdict(
        engineKey: String,
        verdict: Boolean,
    ) {
        if (engineKey.isEmpty()) return
        preferences.edit().putBoolean(rangeVerdictKey(engineKey), verdict).apply()
    }

    override fun msPerUnitAt1x(engineKey: String): Double? {
        if (engineKey.isEmpty()) return null
        val stored = preferences.getFloat(msPerUnitKey(engineKey), MISSING_MS_PER_UNIT)
        return stored.toDouble().takeIf { it > 0 }
    }

    override fun setMsPerUnitAt1x(
        engineKey: String,
        value: Double,
    ) {
        if (engineKey.isEmpty() || value <= 0) return
        preferences.edit().putFloat(msPerUnitKey(engineKey), value.toFloat()).apply()
    }

    private fun rangeVerdictKey(engineKey: String) = "$KEY_RANGE_VERDICT.$engineKey"

    private fun msPerUnitKey(engineKey: String) = "$KEY_MS_PER_UNIT.$engineKey"

    internal companion object {
        internal const val PREFERENCES_NAME = "tts_timing_preferences"
        private const val KEY_RANGE_VERDICT = "range_verdict"
        private const val KEY_MS_PER_UNIT = "ms_per_unit_at_1x"
        private const val MISSING_MS_PER_UNIT = -1f
    }
}
