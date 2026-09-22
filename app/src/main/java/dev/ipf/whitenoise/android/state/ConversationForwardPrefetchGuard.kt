package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Why a forward page was asked for, which decides how its failure is treated (#2764). */
enum class ConversationPagingOrigin {
    /** The reader asked for newer content, or retried a newer page that failed. */
    EXPLICIT,

    /** The viewport drifted to the newest edge on its own, e.g. right after a send. */
    AUTOMATIC,
}

/**
 * How many automatic forward pages may fail in a row before the prefetch stands down.
 *
 * Each attempt already waits out a not-ready window internally, so a small budget is enough to
 * ride out a transient engine gap while staying far short of a per-frame retry loop.
 */
internal const val CONVERSATION_AUTOMATIC_NEWER_PAGE_ATTEMPTS = 3

/**
 * Bounds the opportunistic forward prefetch after it fails (#2764).
 *
 * The prefetch fires whenever the viewport sits near the newest edge, which a successful send puts
 * it at, so a forward page the engine cannot answer would otherwise be re-issued on every layout
 * pass. This is the forward counterpart of the older-page block, with one difference: it holds off
 * only the automatic prefetch. A deliberate newer-page navigation or retry proceeds immediately and
 * keeps its own visible retry affordance.
 *
 * Any forward page that advances releases the block, so a later live-window update recovers it
 * without the reader doing anything.
 */
internal class AutomaticNewerPagingGuard(
    private val budget: Int = CONVERSATION_AUTOMATIC_NEWER_PAGE_ATTEMPTS,
) {
    /** Consecutive automatic forward pages that failed since the last one that advanced. */
    var consecutiveFailures by mutableIntStateOf(0)
        private set

    /** Whether the automatic prefetch should stand down until something advances the window. */
    val blocked: Boolean
        get() = consecutiveFailures >= budget

    /** Counts one failed automatic forward page against the recovery budget. */
    fun recordFailure() {
        if (consecutiveFailures < budget) consecutiveFailures += 1
    }

    /** Releases the block after a forward page advances the window. */
    fun reset() {
        consecutiveFailures = 0
    }
}
