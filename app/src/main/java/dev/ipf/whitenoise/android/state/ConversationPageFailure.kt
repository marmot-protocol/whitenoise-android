package dev.ipf.whitenoise.android.state

import android.util.Log
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
            reportPageFailure(direction, MarmotWindowDeadline(direction), origin)
            ConversationPageLoad.TIMED_OUT
        }
        NOT_READY -> {
            reportPageFailure(direction, MarmotWindowNotReady(direction), origin)
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
 * An automatic forward prefetch reports nothing to the reader: its content is opportunistic, so it
 * only counts against the recovery budget and leaves a privacy-safe marker — the operation code and
 * the attempt number, never the engine's message — in the log.
 */
internal fun ConversationController.reportPageFailure(
    direction: ConversationSearchPageDirection,
    cause: Throwable,
    origin: ConversationPagingOrigin = ConversationPagingOrigin.EXPLICIT,
) {
    if (origin == ConversationPagingOrigin.AUTOMATIC) {
        automaticPaging.newer.recordFailure()
        val attempt = automaticPaging.newer.consecutiveFailures
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
