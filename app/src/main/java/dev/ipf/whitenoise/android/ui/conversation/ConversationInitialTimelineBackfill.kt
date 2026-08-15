package dev.ipf.whitenoise.android.ui.conversation

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

/**
 * Walks backward through raw timeline pages until the conversation has a row
 * Compose can render. Edit records are derived state and can otherwise fill a
 * bounded subscription window without giving the initial anchor a target.
 *
 * The caller owns lifecycle cancellation. [isCurrent] is an additional guard
 * for a controller replacement that races a non-cancellable binding call.
 */
internal suspend fun backfillInitialConversationTimeline(
    snapshot: () -> ConversationInitialTimelineBackfillSnapshot,
    loadOlder: suspend () -> Boolean,
    isCurrent: () -> Boolean = { true },
): ConversationInitialTimelineBackfillResult {
    var current = snapshot()
    when {
        !isCurrent() -> return ConversationInitialTimelineBackfillResult.Superseded
        current.hasRenderableRows -> return ConversationInitialTimelineBackfillResult.Renderable
        current.hasLoadFailure -> return ConversationInitialTimelineBackfillResult.Failed
        current.loadInFlight -> return ConversationInitialTimelineBackfillResult.NotReady
    }
    val visitedWindows = hashSetOf(current.rawWindowMessageIds)

    while (current.hasMoreBefore) {
        val madeProgress = loadOlder()
        if (!isCurrent()) return ConversationInitialTimelineBackfillResult.Superseded

        current = snapshot()
        when {
            current.hasLoadFailure -> return ConversationInitialTimelineBackfillResult.Failed
            current.hasRenderableRows -> return ConversationInitialTimelineBackfillResult.Renderable
            !current.hasMoreBefore -> return ConversationInitialTimelineBackfillResult.Exhausted
            current.loadInFlight -> return ConversationInitialTimelineBackfillResult.NotReady
            !madeProgress || !visitedWindows.add(current.rawWindowMessageIds) ->
                return ConversationInitialTimelineBackfillResult.NoProgress
        }
    }

    return ConversationInitialTimelineBackfillResult.Exhausted
}
