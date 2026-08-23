package dev.ipf.whitenoise.android.audio.tts

/**
 * Persistence for what the timing lane has learned about a TTS engine: whether
 * it reports word ranges, and how fast its voice actually speaks.
 *
 * Both facts are engine capabilities, not protocol data — Android platform
 * preferences in the same category as the engine choice itself. They are keyed
 * by engine package because a different engine is a different capability:
 * crediting one engine's callbacks to another would permanently disable the
 * estimated highlight on a silent engine, or run it against a voice it was
 * never calibrated for.
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
