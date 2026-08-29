package dev.ipf.whitenoise.android.audio.tts

/**
 * Learns whether the active TTS engine actually reports usable word timing.
 *
 * `UtteranceProgressListener.onRangeStart` is delivered only if the engine
 * supplies timing by calling `SynthesisCallback.rangeStart`. Many engines
 * never do, and some devices ship no range-capable engine at all, so whichever
 * engine the user selected decides whether engine-driven word highlighting is
 * possible. No client-side code can conjure the callback; instead the engine's
 * behaviour is detected so the estimated schedule can stand in exactly when it
 * is needed and yield permanently the moment a real range arrives.
 *
 * "Usable" is the right question and is deliberately narrower than "reported":
 * an engine whose ranges cannot be resolved to a whole visible word — because
 * it reports phrase or sub-word granularity — gives the reader nothing, and the
 * estimated schedule standing in for it is the correct outcome.
 *
 * Two properties are easy to get wrong, and both were:
 *
 * **Only evidence that could have gone either way counts.** Silence is evidence
 * of an incapable engine only when the utterance contained a word the engine
 * could have named. An utterance with no visible-text mapping, or one whose
 * only words sit in a sender announcement, proves nothing about the engine, and
 * counting it lets one surface persist a verdict every other surface inherits.
 * The caller decides what was answerable; see [onUtteranceDone].
 *
 * **A verdict restored from storage is provisional.** Range capability is
 * stored per engine package, but it is not a property of the package alone: a
 * locale that falls back to another voice, a voice switched inside the same
 * engine, or an engine update can end it. A stored verdict that could never be
 * re-examined would leave the word marker gone for every future session with no
 * path back, so a restored verdict is held until this attachment either
 * confirms it or accumulates enough answerable silence to overturn it.
 *
 * Evidence ACCUMULATES across utterances: a run of short messages is just as
 * conclusive as one long one. Requiring a single long utterance would leave a
 * chat full of short messages without a verdict forever.
 */
internal class TtsRangeCapabilityProbe(
    /** Answerable characters with no usable range before declaring the engine silent. */
    private val charsToConclude: Int = DEFAULT_CHARS_TO_CONCLUDE,
    /** Answerable characters before overturning a verdict restored from storage. */
    private val charsToOverturn: Int = DEFAULT_CHARS_TO_OVERTURN,
) {
    /** null while unknown, true once a usable range arrives, false once clearly absent. */
    var reportsRanges: Boolean? = null
        private set

    /**
     * Whether this attachment has seen the verdict happen, rather than read it.
     * A restored verdict starts unconfirmed and keeps being examined.
     */
    var isConfirmed: Boolean = false
        private set

    /** True only when this attachment has itself observed usable range timing. */
    val hasConfirmedRangeCapability: Boolean
        get() = reportsRanges == true && isConfirmed

    private var sawRangeForCurrentUtterance = false
    private var silentChars = 0

    fun onUtteranceStart() {
        sawRangeForCurrentUtterance = false
    }

    fun onRangeStart() {
        sawRangeForCurrentUtterance = true
        silentChars = 0
        reportsRanges = true
        isConfirmed = true
    }

    /**
     * Records one finished utterance. [answerableLength] is how much of it the
     * engine could have named a visible word in — never the payload's raw
     * length, which counts sender announcements, emoji and text with no visible
     * mapping as though the engine had stayed silent through real words.
     *
     * Returns true when this utterance CHANGED the verdict, so it can be
     * persisted. A verdict that is merely re-confirmed writes nothing.
     */
    fun onUtteranceDone(answerableLength: Int): Boolean {
        if (isConfirmed || sawRangeForCurrentUtterance) return false
        silentChars += answerableLength.coerceAtLeast(0)
        // Overturning what storage claims needs more evidence than establishing
        // a verdict from nothing: the stored value was itself earned this way.
        val threshold = if (reportsRanges == true) charsToOverturn else charsToConclude
        val concluded = silentChars >= threshold
        val changed = concluded && reportsRanges != false
        if (concluded) {
            reportsRanges = false
            isConfirmed = true
        }
        return changed
    }

    /**
     * Seeds the verdict persisted for this engine from an earlier session.
     *
     * The verdict is cheap to re-learn but not free for the listener: proving
     * an engine silent takes ~120 answerable characters, which is the first
     * message or two of every session after the process was recycled — exactly
     * where a missing word highlight is most visible. It is restored
     * unconfirmed, so it still has to hold up.
     */
    fun restore(verdict: Boolean?) {
        reportsRanges = verdict
        isConfirmed = false
        sawRangeForCurrentUtterance = false
        silentChars = 0
    }

    private companion object {
        /**
         * Roughly a message or two of ordinary prose. Lower than the 120 raw
         * payload characters this used to count, because the quantity changed:
         * only letters and digits inside a visible span count now, which is
         * about four fifths of English prose. Holding the number would have
         * quietly raised the bar and left the first session of a silent engine
         * longer without a word marker, which is the cost this probe exists to
         * keep small.
         */
        const val DEFAULT_CHARS_TO_CONCLUDE = 96

        /** Twice the above; overturning stored evidence should cost more than establishing it. */
        const val DEFAULT_CHARS_TO_OVERTURN = 192
    }
}
