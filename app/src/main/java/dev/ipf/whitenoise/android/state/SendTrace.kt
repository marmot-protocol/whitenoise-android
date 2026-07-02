package dev.ipf.whitenoise.android.state

import java.util.concurrent.atomic.AtomicLong

/**
 * DEBUG-only bookkeeping for one in-flight optimistic text send (issue #913):
 * the send's one-run trace [sequence] and the monotonic ([android.os.SystemClock.elapsedRealtime])
 * millisecond [startMs] at which it was accepted. Lets the engine-echo reconcile
 * that flips the bubble pending → sent measure the accepted → echoed latency.
 * Holds no White Noise protocol data.
 */
internal data class SendTraceEntry(
    val sequence: String,
    val startMs: Long,
)

/**
 * Pure, privacy-safe formatting + id helpers for the text-send latency trace
 * (issue #913: "clock → sent flip lingers for seconds per message; back-to-back
 * sends don't pipeline").
 *
 * This deliberately owns NO Android types (`Log`, `SystemClock`) so it stays a
 * plain-JVM unit under `:app:testDevDebugUnitTest`; the [ConversationController]
 * send path supplies the monotonic timestamps and emits the formatted lines via
 * the existing `DMConversation` `Log` tag, gated on `BuildConfig.DEBUG`.
 *
 * PRIVACY: everything produced here is a phase name, a boolean, a duration in
 * milliseconds, a small count, or a per-process sequence id — never message
 * plaintext, group/account/message ids, pubkeys/npubs, or relay URLs. The
 * sequence id is a monotonically increasing per-process counter (reset every
 * app launch), so it correlates the phases of ONE send within a single logcat
 * run without identifying anything durable.
 */
internal object SendTrace {
    /** Process-lifetime monotonic counter backing [nextSequence]. */
    private val sequenceCounter = AtomicLong(0L)

    /** Log tag phase-prefix, kept short so back-to-back sends stay greppable. */
    const val TAG_PREFIX: String = "send-trace"

    /**
     * A short, one-run-only trace id for a single send, e.g. `s#7`. Correlates
     * the accepted / ffi-start / ffi-return / sent-flip phases of the same
     * message across the async send path without any durable identifier.
     * Wraps defensively at [Long.MAX_VALUE] back to 1 so an extremely long
     * session can't overflow into a negative id.
     */
    fun nextSequence(): String {
        val next = sequenceCounter.updateAndGet { if (it == Long.MAX_VALUE) 1L else it + 1L }
        return "s#$next"
    }

    /** Reset the counter. Test-only hook so ids are deterministic per case. */
    fun resetSequenceForTest(value: Long = 0L) {
        sequenceCounter.set(value.coerceAtLeast(0L))
    }

    /**
     * Completion phase for the send() success path. Only call it `sent-flip`
     * when that path actually flips the local pending bubble; if the engine
     * echo already reconciled the bubble, the visible flip was the earlier
     * `echo-reconcile` phase and this later success event is just completion.
     */
    fun completionPhase(insertedSentBubble: Boolean): String = if (insertedSentBubble) "sent-flip" else "send-complete"

    /**
     * Format a single trace line for [sequence] at [phase], with the elapsed
     * milliseconds since the send began ([sinceStartMs]) and, when this phase
     * closes a timed sub-span, the duration of that span ([spanMs]). Extra
     * privacy-safe key/value context (counts, booleans, short enums) is appended
     * verbatim; callers must not pass sensitive values.
     *
     * Example: `s#7 ffi-return +842ms span=838ms attempt=1 retried=false`.
     */
    fun line(
        sequence: String,
        phase: String,
        sinceStartMs: Long,
        spanMs: Long? = null,
        vararg context: Pair<String, Any?>,
    ): String =
        buildString {
            append(sequence)
            append(' ')
            append(phase)
            append(" +")
            append(sinceStartMs.coerceAtLeast(0))
            append("ms")
            if (spanMs != null) {
                append(" span=")
                append(spanMs.coerceAtLeast(0))
                append("ms")
            }
            context.forEach { (key, value) ->
                if (value != null) {
                    append(' ')
                    append(key)
                    append('=')
                    append(value)
                }
            }
        }
}
