package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long one forward phase may run before its progress says it is taking longer than usual (#2556). The notice
 * only changes what is shown, it never cancels, fails or retries the work, so a native publish stays authoritative
 * and is never abandoned or sent twice.
 */
internal const val FORWARD_PHASE_SLOW_MILLIS = 15_000L

/**
 * Runs [block] while a timer calls [markSlow] once [block] has been running for [slowMillis]. The timer is a child
 * of this call, so it stops as soon as [block] returns or throws and whenever the attempt itself is cancelled — an
 * earlier attempt's timer can never mark a later retry slow.
 */
internal suspend fun <T> withSlowPhaseNotice(
    markSlow: () -> Unit,
    slowMillis: Long = FORWARD_PHASE_SLOW_MILLIS,
    block: suspend () -> T,
): T =
    coroutineScope {
        val timer =
            launch {
                delay(slowMillis)
                markSlow()
            }
        try {
            block()
        } finally {
            timer.cancel()
        }
    }
