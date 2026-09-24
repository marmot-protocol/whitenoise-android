package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationWindowUnchangedReason.NOT_READY
import dev.ipf.whitenoise.android.state.ConversationWindowUnchangedReason.TIMED_OUT

/**
 * Turns an unchanged window into a page outcome, leaving the reader a retry affordance for the two
 * reasons that are worth telling them about.
 *
 * A deadline and an exhausted retry budget are the engine failing to answer, not the end of history,
 * so they set the same error the throwing path does. Superseded and terminal outcomes mean a newer
 * revision or the reconnect loop already owns the answer, and a banner would only be noise.
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
        automaticNewerPaging.recordFailure()
        val attempt = automaticNewerPaging.consecutiveFailures
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
