package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.isLoadingPage

internal data class ConversationInitialTimelineBackfillSnapshot(
    val hasRenderableRows: Boolean,
    val hasMoreBefore: Boolean,
    val loadInFlight: Boolean,
    val hasLoadFailure: Boolean,
    val rawWindowMessageIds: List<String>,
)

internal enum class ConversationInitialTimelineBackfillResult {
    Renderable,
    Exhausted,
    Failed,
    NoProgress,
    NotReady,
    Superseded,
}

/** Captures only the bounded state needed to decide whether startup backfill can progress. */
internal fun ConversationController.initialTimelineBackfillSnapshot() =
    ConversationInitialTimelineBackfillSnapshot(
        hasRenderableRows = timeline.any(::conversationTimelineRowVisible),
        hasMoreBefore = hasMoreBefore,
        loadInFlight = isLoadingPage || (isLoading && !hasPublishedAuthoritativeTimeline),
        hasLoadFailure = error != null,
        rawWindowMessageIds =
            timelineSubscription
                ?.latestInstalledWindow()
                ?.page
                ?.messages
                ?.map { it.messageIdHex }
                ?: timeline.map { it.record.messageIdHex },
    )

/**
 * Walks backward through raw timeline pages until the conversation has a row
 * Compose can render. Edits and background agent events can otherwise fill a
 * bounded subscription window. Anchor at the oldest loaded record so MDK
 * keeps the newly fetched history when it trims a full window.
 *
 * The caller owns lifecycle cancellation. [isCurrent] is an additional guard
 * for a controller replacement that races a non-cancellable binding call.
 */
internal suspend fun backfillInitialConversationTimeline(
    snapshot: () -> ConversationInitialTimelineBackfillSnapshot,
    loadOlder: suspend (anchorMessageId: String?) -> Boolean,
    isCurrent: () -> Boolean = { true },
): ConversationInitialTimelineBackfillResult {
    var current = snapshot()
    var result = initialBackfillTerminalResult(current, isCurrent())
    val visitedWindows = hashSetOf(current.rawWindowMessageIds)

    while (result == null && current.hasMoreBefore) {
        val madeProgress = loadOlder(current.rawWindowMessageIds.firstOrNull())
        if (!isCurrent()) {
            result = ConversationInitialTimelineBackfillResult.Superseded
        } else {
            current = snapshot()
            result =
                when {
                    current.hasLoadFailure -> ConversationInitialTimelineBackfillResult.Failed
                    current.hasRenderableRows -> ConversationInitialTimelineBackfillResult.Renderable
                    !current.hasMoreBefore -> ConversationInitialTimelineBackfillResult.Exhausted
                    current.loadInFlight -> ConversationInitialTimelineBackfillResult.NotReady
                    !madeProgress || !visitedWindows.add(current.rawWindowMessageIds) ->
                        ConversationInitialTimelineBackfillResult.NoProgress
                    else -> null
                }
        }
    }

    return result ?: ConversationInitialTimelineBackfillResult.Exhausted
}

private fun initialBackfillTerminalResult(
    snapshot: ConversationInitialTimelineBackfillSnapshot,
    isCurrent: Boolean,
): ConversationInitialTimelineBackfillResult? =
    when {
        !isCurrent -> ConversationInitialTimelineBackfillResult.Superseded
        snapshot.hasRenderableRows -> ConversationInitialTimelineBackfillResult.Renderable
        snapshot.hasLoadFailure -> ConversationInitialTimelineBackfillResult.Failed
        snapshot.loadInFlight -> ConversationInitialTimelineBackfillResult.NotReady
        else -> null
    }
