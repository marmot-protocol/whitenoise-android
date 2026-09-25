package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Why a page was asked for, which decides how its failure is treated (#2764). */
enum class ConversationPagingOrigin {
    /** The reader asked for more content, or retried a page that failed. */
    EXPLICIT,

    /** The viewport drifted to an edge on its own, e.g. right after a send or at the end of a fling. */
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
 * How many automatic older pages may come back without new rows before the prefetch stands down.
 *
 * One is enough: a page the engine answered with nothing older is an answer, not a failure to
 * answer, so asking again on the next layout pass only repeats it (#2727). Not-ready and deadline
 * outcomes never reach this budget, because they arm the visible retry row instead.
 */
internal const val CONVERSATION_AUTOMATIC_OLDER_PAGE_ATTEMPTS = 1

/**
 * Bounds an opportunistic prefetch in one direction after it stops making progress (#2764).
 *
 * The prefetch fires whenever the viewport sits near a loaded edge, which a successful send or the
 * end of history puts it at, so a page the engine cannot answer, or answers without new rows, would
 * otherwise be re-issued on every layout pass. This holds off only the automatic prefetch. A
 * deliberate navigation or retry proceeds immediately and keeps its own visible retry affordance.
 *
 * Any page in the same direction that advances releases the block, so a later live-window update
 * recovers it without the reader doing anything.
 */
internal class AutomaticPagingGuard(
    private val budget: Int,
) {
    /** Consecutive automatic pages that made no progress since the last one that advanced. */
    var consecutiveFailures by mutableIntStateOf(0)
        private set

    /** Whether the automatic prefetch should stand down until something advances the window. */
    val blocked: Boolean
        get() = consecutiveFailures >= budget

    /** Counts one automatic page that made no progress against the recovery budget. */
    fun recordFailure() {
        if (consecutiveFailures < budget) consecutiveFailures += 1
    }

    /** Releases the block after a page in this direction advances the window. */
    fun reset() {
        consecutiveFailures = 0
    }
}

/** The two per-direction guards a conversation keeps, released together by an authoritative window. */
internal class AutomaticPagingGuards {
    val newer = AutomaticPagingGuard(CONVERSATION_AUTOMATIC_NEWER_PAGE_ATTEMPTS)
    val older = AutomaticPagingGuard(CONVERSATION_AUTOMATIC_OLDER_PAGE_ATTEMPTS)

    /** Releases both directions; a live replacement is the recovery either prefetch was waiting for. */
    fun reset() {
        newer.reset()
        older.reset()
    }
}
