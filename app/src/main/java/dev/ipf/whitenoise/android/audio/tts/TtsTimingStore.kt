package dev.ipf.whitenoise.android.audio.tts

import java.util.Locale

/**
 * Persistence for what the timing lane has learned about a TTS engine: whether
 * it reports word ranges, and how fast its voice actually speaks.
 *
 * Both facts are engine capabilities, not protocol data — Android platform
 * preferences in the same category as the engine choice itself. Pace is keyed
 * by engine package. Range timing is keyed by engine package plus locale,
 * because setLanguage can select a voice with different callback support
 * without replacing the engine instance.
 */
interface TtsTimingStore {
    /** Persisted range verdict for [engineKey]; null while never concluded. */
    fun rangeVerdict(engineKey: String): Boolean?

    fun setRangeVerdict(
        engineKey: String,
        verdict: Boolean,
    )

    /** Learned milliseconds per speech unit at 1x for [engineKey]; null while unmeasured. */
    fun msPerUnitAt1x(engineKey: String): Double?

    fun setMsPerUnitAt1x(
        engineKey: String,
        value: Double,
    )
}

/** Stable persistence key for the smallest synthesis context Android exposes here. */
internal fun ttsRangeVerdictKey(
    engineKey: String,
    locale: Locale,
): String = if (engineKey.isEmpty()) "" else "$engineKey|locale=${locale.toLanguageTag()}"
