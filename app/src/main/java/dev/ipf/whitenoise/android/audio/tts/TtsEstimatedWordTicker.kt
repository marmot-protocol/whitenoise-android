package dev.ipf.whitenoise.android.audio.tts

import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Advances the word highlight on a timer for engines that never call
 * `onRangeStart`, by replaying an estimated schedule as synthetic range
 * callbacks for one utterance.
 *
 * The clock is ABSOLUTE: a loop that sleeps word by word overshoots by
 * scheduler latency on every sleep, and fifteen words in the overshoots sum to
 * a visible lag. Recomputing the position from one start timestamp cannot
 * accumulate error.
 *
 * The ticker knows nothing about queue state on purpose. Its synthetic events
 * carry the utterance id they were armed with and flow through the exact same
 * validation the engine's own callbacks pass, so a stale tick after a stop,
 * pause, or navigation is inert for the same reason a stale engine callback
 * is. [emit] returning false stops the loop — the owner says so when a real
 * engine range takes over or the utterance ends.
 */
internal class TtsEstimatedWordTicker(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    /**
     * Starts replaying [words] for [utteranceId], replacing any previous
     * schedule. Call this when the engine reports the utterance has STARTED,
     * not when it was enqueued: engines take a noticeable moment to begin
     * speaking — longest on the first utterance while the voice warms up — and
     * a schedule anchored at submission time runs ahead of the audio for the
     * whole first sentence.
     */
    @Synchronized
    fun start(
        utteranceId: String,
        words: List<TtsEstimatedWord>,
        emit: (utteranceId: String, start: Int, end: Int) -> Boolean,
    ) {
        job?.cancel()
        if (words.isEmpty()) {
            job = null
            return
        }
        val startedAt = clock()
        val horizonMs = words.last().startMs + SCHEDULE_GRACE_MS
        job =
            scope.launch {
                var published: TtsEstimatedWord? = null
                while (true) {
                    // onStart means "synthesis has begun", not "sound has
                    // started": the lead-in absorbs the audio pipeline's
                    // latency so the first word is not highlighted early. The
                    // UI lead looks the word up slightly ahead of the audio
                    // clock to cover the publish→recompose→draw pipeline.
                    val elapsed = clock() - startedAt - LEAD_IN_MS + UI_LEAD_MS
                    if (elapsed > horizonMs) return@launch
                    val word = words.lastOrNull { it.startMs <= elapsed }
                    if (word != null && word != published) {
                        published = word
                        if (!emit(utteranceId, word.start, word.end)) return@launch
                    }
                    delay(TICK_MS)
                }
            }
    }

    /** Stops the active schedule, if any. Safe to call from any thread. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Cancels the ticker permanently. */
    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        /** Re-derive the active word at this cadence; small enough to never skip a short word. */
        const val TICK_MS = 48L

        /** Audio-pipeline latency between "utterance started" and audible speech. */
        const val LEAD_IN_MS = 160L

        /** Publish→recompose→draw takes a few dozen ms; look the word up slightly ahead. */
        const val UI_LEAD_MS = 70L

        /** Keep ticking a little past the schedule for a slow engine, then stop on our own. */
        const val SCHEDULE_GRACE_MS = 10_000L
    }
}
