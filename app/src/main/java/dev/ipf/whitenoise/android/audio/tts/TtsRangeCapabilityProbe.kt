package dev.ipf.whitenoise.android.audio.tts

/**
 * Learns whether the active TTS engine actually reports word timing.
 *
 * `UtteranceProgressListener.onRangeStart` is delivered only if the engine
 * supplies timing by calling `SynthesisCallback.rangeStart`. Many engines
 * never do, and some devices ship no range-capable engine at all, so whichever
 * engine the user selected decides whether engine-driven word highlighting is
 * possible. No client-side code can conjure the callback; instead the engine's
 * behaviour is detected so the estimated schedule can stand in exactly when it
 * is needed and yield permanently the moment a real range arrives.
 *
 * Evidence ACCUMULATES across utterances: a run of short messages is just as
 * conclusive as one long one. Requiring a single long utterance would leave a
 * chat full of short messages without a verdict forever.
 */
internal class TtsRangeCapabilityProbe(
    /** Total spoken characters with no range callback before declaring the engine silent. */
    private val charsToConclude: Int = DEFAULT_CHARS_TO_CONCLUDE,
) {
    /** null while unknown, true once a range callback arrives, false once clearly absent. */
    var reportsRanges: Boolean? = null
        private set

    private var sawRangeForCurrentUtterance = false
    private var silentChars = 0

    fun onUtteranceStart() {
        sawRangeForCurrentUtterance = false
    }

    fun onRangeStart() {
        sawRangeForCurrentUtterance = true
        silentChars = 0
        reportsRanges = true
    }

    /** Returns true when this utterance settled the verdict, so it can be persisted. */
    fun onUtteranceDone(spokenLength: Int): Boolean {
        if (reportsRanges != null || sawRangeForCurrentUtterance) return false
        silentChars += spokenLength.coerceAtLeast(0)
        val concluded = silentChars >= charsToConclude
        if (concluded) reportsRanges = false
        return concluded
    }

    /**
     * Seeds the verdict persisted for this engine from an earlier session.
     *
     * The verdict is cheap to re-learn but not free for the listener: proving
     * an engine silent takes ~120 spoken characters, which is the first
     * message or two of every session after the process was recycled — exactly
     * where a missing word highlight is most visible.
     */
    fun restore(verdict: Boolean?) {
        reportsRanges = verdict
        sawRangeForCurrentUtterance = false
        silentChars = 0
    }

    private companion object {
        const val DEFAULT_CHARS_TO_CONCLUDE = 120
    }
}
