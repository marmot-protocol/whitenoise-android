package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.util.concurrent.CancellationException

/** Performs the explicit newest-message action without bypassing coordinator ownership. */
internal suspend fun ConversationScrollCoordinator.jumpToNewest(
    targetIndex: Int,
    resolveTailIndex: () -> Int? = { targetIndex },
): Boolean =
    programmaticJump(
        targetMessageId = null,
        reason = ConversationScrollReason.JumpToNewest,
        resultingMode = ConversationScrollMode.FollowingTail,
    ) {
        animateScrollToTail(targetIndex, resolveTailIndex)
    }

/**
 * First tap: top-align the frozen unread row while retaining history-reading
 * mode. A missing/consumed target falls through to the physical tail in the
 * same tap. Cancellation deliberately preserves the caller's pending target.
 */
@Suppress("ReturnCount") // Guard clauses preserve the ordered fallback/cancellation contract.
internal suspend fun ConversationScrollCoordinator.jumpToUnreadOrNewest(
    pendingUnreadMessageId: String?,
    resolveUnreadIndex: () -> Int?,
    isUnreadTopAligned: () -> Boolean,
    prepareTail: suspend () -> Boolean = { true },
    resolveTailIndex: () -> Int,
): ConversationJumpToNewestOutcome {
    suspend fun jumpToTail(): ConversationJumpToNewestOutcome {
        var tailPrepared = false
        val completed =
            programmaticJump(
                targetMessageId = null,
                reason = ConversationScrollReason.UnreadTail,
                resultingMode = ConversationScrollMode.FollowingTail,
            ) {
                tailPrepared = prepareTail()
                if (!tailPrepared) {
                    throw CancellationException("Conversation newest edge was not available")
                }
                animateScrollToTail(resolveTailIndex(), resolveTailIndex)
            }
        return if (completed && tailPrepared) {
            ConversationJumpToNewestOutcome.Tail
        } else {
            ConversationJumpToNewestOutcome.Cancelled
        }
    }

    val targetMessageId = pendingUnreadMessageId ?: return jumpToTail()
    val initialTargetIndex = resolveUnreadIndex() ?: return jumpToTail()
    if (isUnreadTopAligned()) return jumpToTail()

    var targetResolved = false
    val completed =
        programmaticJump(
            targetMessageId = targetMessageId,
            reason = ConversationScrollReason.UnreadStart,
            resultingMode = ConversationScrollMode.ReadingHistory(targetMessageId, 0),
        ) {
            targetResolved =
                animateScrollToItem(initialTargetIndex, 0, resolveIndex = resolveUnreadIndex)
            // The reversed transcript reaches a row by its newest edge, so an
            // unread message taller than the viewport would open at its end.
            if (targetResolved) alignReadingStart(resolveUnreadIndex() ?: initialTargetIndex)
        }
    if (!completed) return ConversationJumpToNewestOutcome.Cancelled
    if (!targetResolved || resolveUnreadIndex() == null) return jumpToTail()
    return if (isUnreadTopAligned()) {
        ConversationJumpToNewestOutcome.UnreadStart
    } else {
        // A completed but clamped first-stage scroll is not cancellation. The
        // target cannot be represented as the top-aligned destination, so
        // finish this tap at the tail instead of arming a repeat scroll.
        jumpToTail()
    }
}

/**
 * Processes a newer drag immediately, cancelling any older Stop/Cancel waiter
 * that is still waiting for fling motion to finish.
 *
 * The drag stops when the finger lifts, and Compose starts the fling only after
 * it has reported that stop, so at that instant the list reads as idle although
 * it is about to coast. [awaitFrame] lets the fling begin before
 * [awaitScrollSettled] is consulted, otherwise a flick would settle at its
 * release point and hand history re-anchoring a stale anchor to snap the
 * coasting transcript back to (#2727).
 */
internal suspend fun Flow<Interaction>.collectConversationDragInteractions(
    onStarted: () -> Unit,
    awaitScrollSettled: suspend () -> Unit,
    onSettled: () -> Unit,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
) {
    filter { interaction ->
        interaction is DragInteraction.Start ||
            interaction is DragInteraction.Stop ||
            interaction is DragInteraction.Cancel
    }.collectLatest { interaction ->
        when (interaction) {
            is DragInteraction.Start -> onStarted()
            is DragInteraction.Stop,
            is DragInteraction.Cancel,
            -> {
                awaitFrame()
                awaitScrollSettled()
                onSettled()
            }
        }
    }
}

/**
 * Reports each scroll run that nothing in the app started as the reader's own movement.
 *
 * Screen-reader scroll actions, focus moving past the last visible row, keyboard paging and mouse wheels
 * scroll the list without a [DragInteraction], so [collectConversationDragInteractions] never sees them
 * and a tail follower would be pulled back to the newest message by the next append or remeasure (#3603).
 * Collect [listState.isScrollInProgress][LazyListState.isScrollInProgress] here. A run is ignored when
 * [claimedByApp] holds as it starts: a coordinator command, the read-aloud hold or drag-selection edge
 * scrolling. A touch drag reports its own start and settle, but its interaction can reach the drag
 * collector after the scroll is already visible, so [touchDragActive] is checked again one frame later.
 * A drag that begins inside a claimed run takes that run over, so the drag alone settles it.
 */
internal suspend fun Flow<Boolean>.collectUnclaimedScrollRuns(
    claimedByApp: () -> Boolean,
    touchDragActive: () -> Boolean,
    onStarted: () -> Unit,
    onSettled: () -> Unit,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
) {
    var readerRun = false
    distinctUntilChanged().collect { scrolling ->
        if (scrolling) {
            if (claimedByApp() || touchDragActive()) return@collect
            awaitFrame()
            if (claimedByApp() || touchDragActive()) return@collect
            readerRun = true
            onStarted()
        } else if (readerRun) {
            readerRun = false
            if (!touchDragActive()) onSettled()
        }
    }
}

/**
 * Positions an initial target while the transcript is hidden, waits until both
 * the viewport and target row have stable measured geometry, then commits the
 * same position once more. Callers may safely reveal only when this returns
 * true; false means geometry did not stabilize within this attempt.
 */
internal suspend fun ConversationScrollCoordinator.commitInitialAnchor(
    targetMessageId: String?,
    reason: ConversationScrollReason,
    resultingMode: ConversationScrollMode,
    targetIndex: Int,
    pixelOffset: Int = 0,
    captureLayout: () -> ConversationInitialAnchorLayout,
    maxSettleFrames: Int = 24,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
): Boolean {
    var layoutStabilized = false
    val commandCompleted =
        programmaticJump(
            targetMessageId = targetMessageId,
            reason = reason,
            resultingMode = resultingMode,
        ) {
            scrollToItem(targetIndex, pixelOffset)
            layoutStabilized =
                awaitStableInitialAnchorLayout(
                    captureLayout = captureLayout,
                    maxFrames = maxSettleFrames,
                    awaitFrame = awaitFrame,
                )
            if (layoutStabilized) {
                scrollToItem(targetIndex, pixelOffset)
            }
        }
    return commandCompleted && layoutStabilized
}

/**
 * Positions a notified message's reading start while the transcript is hidden: one non-animated
 * write, one layout frame, then the shared measured settle, so the first reveal is already on
 * target and no visible corrective snap follows. The result keeps why a commit failed, because a
 * retrying caller must restart a row that was only unmeasurable but never a command that a drag or
 * a newer navigation superseded.
 */
internal suspend fun ConversationScrollCoordinator.commitInitialReadingStartAnchor(
    targetMessageId: String,
    resultingMode: ConversationScrollMode,
    probe: ConversationReadingStartProbe,
    reason: ConversationScrollReason = ConversationScrollReason.NotificationTarget,
): ConversationReadingStartResult =
    settleReadingStart(
        targetMessageId = targetMessageId,
        reason = reason,
        probe = probe,
        approach = ConversationReadingStartApproach.Instant,
        resultingMode = resultingMode,
    )

/**
 * Tail opens do not need the history path's two equal layout samples. Commit
 * the real final row, yield one layout frame, and reveal as soon as the viewport
 * and target are both measured.
 */
internal suspend fun ConversationScrollCoordinator.commitInitialTailAnchor(
    targetIndex: Int,
    captureLayout: () -> ConversationInitialAnchorLayout,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
): Boolean {
    var layoutReady = false
    val commandCompleted =
        programmaticJump(
            targetMessageId = null,
            reason = ConversationScrollReason.InitialAnchor,
            resultingMode = ConversationScrollMode.FollowingTail,
        ) {
            scrollToTail(targetIndex)
            awaitFrame()
            layoutReady = captureLayout().isReady
        }
    return commandCompleted && layoutReady
}

/** Waits a bounded number of frames for the target row and viewport to remain measured. */
private suspend fun awaitStableInitialAnchorLayout(
    captureLayout: () -> ConversationInitialAnchorLayout,
    maxFrames: Int,
    awaitFrame: suspend () -> Unit,
): Boolean {
    var previous: ConversationInitialAnchorLayout? = null
    var stableFrames = 0
    repeat(maxFrames.coerceAtLeast(1)) {
        awaitFrame()
        val current = captureLayout()
        stableFrames =
            if (current.isReady && current == previous) {
                stableFrames + 1
            } else {
                0
            }
        if (stableFrames >= 1) return true
        previous = current
    }
    return false
}
