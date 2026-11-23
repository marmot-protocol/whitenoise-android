package dev.ipf.whitenoise.android.audio

/**
 * Measures the interval from the first accepted Paste or Send until the transcript is ready.
 *
 * Holds durations and counts only, never transcript text. Main-thread confined like the controller.
 */
internal class ConversationDictationFinishTiming {
    private var tapAt: Long? = null
    private var captureClosedAt: Long? = null
    private var lastResultAt: Long? = null
    private var generations = 0L
    private var retries = 0L
    private var settleWaitMillis = 0L
    private var lastResultSample: Long? = null

    /** Keeps the first accepted completion action; a later replacement choice does not restart the clock. */
    fun tap(now: Long) {
        if (tapAt == null) tapAt = now
    }

    fun captureClosed(now: Long) {
        if (tapAt != null && captureClosedAt == null) captureClosedAt = now
    }

    /** Records an accepted result; [lastSampleExclusive] is the end of its caller-audio chunk, if any. */
    fun result(
        now: Long,
        lastSampleExclusive: Long?,
    ) {
        if (lastSampleExclusive != null) {
            lastResultSample = maxOf(lastResultSample ?: 0L, lastSampleExclusive)
        }
        if (tapAt != null) lastResultAt = now
    }

    fun generationStarted() {
        if (tapAt != null) generations += 1
    }

    fun retryScheduled() {
        if (tapAt != null) retries += 1
    }

    fun settleWait(delayMillis: Long) {
        if (tapAt != null) settleWaitMillis += delayMillis
    }

    /** Returns the content-free diagnostic once per tap, or null when no completion action was timed. */
    fun transcriptReady(
        now: Long,
        mode: ConversationDictationDeliveryMode,
    ): String? {
        val tap = tapAt ?: return null
        val action = if (mode == ConversationDictationDeliveryMode.SendOnFinish) "send" else "paste"
        val line =
            buildString {
                append("event=finish_timing action=").append(action)
                captureClosedAt?.let { append(" tap_to_capture_closed_ms=").append(it - tap) }
                lastResultAt?.let { append(" tap_to_last_result_ms=").append(it - tap) }
                append(" tap_to_transcript_ready_ms=").append(now - tap)
                append(" generations_after_tap=").append(generations)
                append(" retries_after_tap=").append(retries)
                append(" settle_wait_ms=").append(settleWaitMillis)
                lastResultSample?.let {
                    append(" audio_ms=").append(it * MILLIS_PER_SECOND / CALLER_AUDIO_SAMPLE_RATE_HZ)
                }
            }
        reset()
        return line
    }

    fun reset() {
        tapAt = null
        captureClosedAt = null
        lastResultAt = null
        generations = 0L
        retries = 0L
        settleWaitMillis = 0L
        lastResultSample = null
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
