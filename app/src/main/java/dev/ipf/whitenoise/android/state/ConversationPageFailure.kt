package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.ConversationWindowRevisionFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationWindowUnchangedReason.NOT_READY
import dev.ipf.whitenoise.android.state.ConversationWindowUnchangedReason.SUPERSEDED
import dev.ipf.whitenoise.android.state.ConversationWindowUnchangedReason.TIMED_OUT

/**
 * Turns an unchanged window into a page outcome, leaving the reader a retry affordance for
 * recoverable reasons.
 *
 * A deadline or an exhausted retry budget is not the end of history, so it sets the same retry
 * affordance as a thrown failure. Terminal outcomes leave the reconnect loop to recover.
 */
internal fun ConversationController.unchangedPageLoad(
    outcome: TimelinePageOutcome.Unchanged,
    direction: ConversationSearchPageDirection,
    origin: ConversationPagingOrigin = ConversationPagingOrigin.EXPLICIT,
): ConversationPageLoad =
    when (outcome.reason) {
        TIMED_OUT -> {
            reportPageFailure(direction, MarmotWindowDeadline(direction), origin, outcome.requestedRevision)
            ConversationPageLoad.TIMED_OUT
        }
        NOT_READY -> {
            reportPageFailure(direction, MarmotWindowNotReady(direction), origin, outcome.requestedRevision)
            ConversationPageLoad.NOT_READY
        }
        SUPERSEDED -> {
            reportPageFailure(direction, MarmotWindowSuperseded(direction), origin)
            ConversationPageLoad.FAILED
        }
        else -> ConversationPageLoad.NO_PROGRESS
    }

/**
 * Records a page failure so the screen's existing retry affordance appears for this direction.
 *
 * An automatic prefetch reports nothing to the reader: its content is opportunistic, so it
 * only counts against the recovery budget and leaves a privacy-safe marker — the operation code and
 * the attempt number, never the engine's message — in the log.
 */
internal fun ConversationController.reportPageFailure(
    direction: ConversationSearchPageDirection,
    cause: Throwable,
    origin: ConversationPagingOrigin = ConversationPagingOrigin.EXPLICIT,
    requestedRevision: ConversationWindowRevisionFfi? = null,
) {
    if (origin == ConversationPagingOrigin.AUTOMATIC) {
        val guard =
            when (direction) {
                ConversationSearchPageDirection.OLDER -> automaticPaging.older
                ConversationSearchPageDirection.NEWER -> automaticPaging.newer
            }
        guard.recordFailure(
            revision = requestedRevision ?: timelineSubscription?.latestWindowFrame()?.revision,
            waitForReplacement = cause is MarmotWindowNotReady || cause is MarmotWindowDeadline,
        )
        val attempt = guard.consecutiveFailures
        // Recovery may already have committed while the not-ready/deadline reply was in flight.
        if (direction == ConversationSearchPageDirection.OLDER && requestedRevision != null) {
            guard.onWindowApplied(window.frame?.revision)
        }
        Log.w("DMConversation", "automatic_page_failed operation=${pageOperation(direction)} attempt=$attempt")
        return
    }
    failedPageDirection = direction
    pageError =
        privacySafeErrorPresentation(
            pageOperation(direction),
            cause,
            AppText.Resource(R.string.error_loaded_content_kept),
        )
}

/** Starts one owned page without dismissing a failure from another direction. */
internal fun ConversationController.beginPageLoad(
    direction: ConversationSearchPageDirection,
    origin: ConversationPagingOrigin,
) {
    if (origin == ConversationPagingOrigin.EXPLICIT) clearRecoveredPageFailure(direction)
    pageLoadInFlight = direction
}

/**
 * Retires only the matching page failure once its direction recovers.
 *
 * Only the matching direction is cleared, so an older-page failure the reader still has a retry row
 * for, and any unrelated subscription error, survive a forward recovery.
 */
internal fun ConversationController.clearRecoveredPageFailure(direction: ConversationSearchPageDirection) {
    if (failedPageDirection != direction) return
    failedPageDirection = null
    pageError = null
}

/** Clears a stale older retry only when committed rows extend past the previously loaded oldest row. */
internal fun ConversationController.clearRecoveredOlderPageFailure(priorOldestId: String?) {
    if (failedPageDirection != ConversationSearchPageDirection.OLDER) return
    if (priorOldestId != null && timeline.indexOfFirst { it.id == priorOldestId } > 0) {
        clearRecoveredPageFailure(ConversationSearchPageDirection.OLDER)
    }
}

/** The release-log operation name for a page in this direction. */
private fun pageOperation(direction: ConversationSearchPageDirection): String =
    when (direction) {
        ConversationSearchPageDirection.OLDER -> "CONVERSATION_PAGE_OLDER"
        ConversationSearchPageDirection.NEWER -> "CONVERSATION_PAGE_NEWER"
    }

/** The window deadline expressed as a throwable, so it reaches the same release marker as a thrown failure. */
private class MarmotWindowDeadline(
    direction: ConversationSearchPageDirection,
) : Exception("window deadline paging ${direction.name.lowercase()}")

/** A window that stayed not-ready for the whole retry budget. */
private class MarmotWindowNotReady(
    direction: ConversationSearchPageDirection,
) : Exception("window not ready paging ${direction.name.lowercase()}")

/** A newer window kept superseding every command in one bounded gesture. */
private class MarmotWindowSuperseded(
    direction: ConversationSearchPageDirection,
) : Exception("window superseded paging ${direction.name.lowercase()}")
