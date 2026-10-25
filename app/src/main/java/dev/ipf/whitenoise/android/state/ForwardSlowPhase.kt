package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * How long a forward phase may go without progress before it says it is taking longer than usual (#2556). Slow means
 * no progress, not a long phase: any progress clears the notice and restarts the window, so a large album that keeps
 * uploading is never called slow. The notice only changes what is shown, it never cancels, fails or retries the work,
 * so a native publish stays authoritative and is never abandoned or sent twice.
 */
internal const val FORWARD_PHASE_SLOW_MILLIS = 15_000L

/**
 * Runs [block] while a timer calls [markSlow] whenever a whole [slowMillis] window passes with [progress] unchanged,
 * so the notice appears at most two windows after the last progress. Callers clear the notice when progress moves.
 * The timer is a child of this call, so it stops as soon as [block] returns or throws and whenever the attempt itself
 * is cancelled — an earlier attempt's timer can never mark a later retry slow.
 */
internal suspend fun <T> withSlowPhaseNotice(
    markSlow: () -> Unit,
    progress: () -> Any?,
    slowMillis: Long = FORWARD_PHASE_SLOW_MILLIS,
    block: suspend () -> T,
): T =
    coroutineScope {
        val timer =
            launch {
                var seen = progress()
                while (true) {
                    delay(slowMillis)
                    val now = progress()
                    if (now == seen) markSlow()
                    seen = now
                }
            }
        try {
            block()
        } finally {
            timer.cancel()
        }
    }

/** The current progress of [groupIdHex]'s destination, read by its slow-phase timer. */
internal fun forwardTarget(
    state: MutableStateFlow<ForwardOperationSnapshot>,
    groupIdHex: String,
): ForwardTargetProgress? = state.value.targets.firstOrNull { it.groupIdHex == groupIdHex }

/** Records new upload or send counts, clearing a slow notice whenever either count actually moves. */
internal fun ForwardTargetProgress.advancedTo(
    uploaded: Int = uploadedAttachments,
    sent: Int = sentMessages,
): ForwardTargetProgress {
    val moved = uploaded != uploadedAttachments || sent != sentMessages
    return copy(uploadedAttachments = uploaded, sentMessages = sent, slow = slow && !moved)
}
