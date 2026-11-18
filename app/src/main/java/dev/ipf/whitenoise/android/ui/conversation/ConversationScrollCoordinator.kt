package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.state.StalenessGuard
import dev.ipf.whitenoise.android.state.tracedPagingSection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.CancellationException
import kotlin.math.abs

private const val MAX_ANIMATED_SCROLL_ITEMS = 10
private const val MAX_TARGET_REPOSITION_ATTEMPTS = 3
private const val DEFAULT_TAIL_LAYOUT_SETTLE_FRAMES = 8
private const val MIN_TAIL_LAYOUT_SETTLE_FRAMES = 4
private const val REQUIRED_STABLE_TAIL_LAYOUT_FRAMES = 2

/** Reasons a target-less jump only keeps a tail follower at the newest edge, never a reader's own navigation. */
private val PASSIVE_TAIL_CORRECTIONS =
    setOf(
        ConversationScrollReason.NewMessage,
        ConversationScrollReason.ReactionLayout,
        ConversationScrollReason.ViewportChange,
        ConversationScrollReason.ImeTransition,
    )

/**
 * The sole mutation boundary for a conversation's [LazyListState].
 *
 * The coordinator keeps durable user intent separate from transient layout
 * geometry. Every command supersedes the previous command, and a drag invalidates
 * its token before cancelling its coroutine so a stale frame chase cannot resume
 * after the gesture.
 */
@Suppress("TooManyFunctions") // Cohesive scroll-intent state machine and its foreground transaction.
internal class ConversationScrollCoordinator(
    private val writer: ConversationScrollWriter,
    initialMode: ConversationScrollMode = ConversationScrollMode.FollowingTail,
    private val onExplicitNavigation: () -> Unit = {},
) {
    private var settledMode = initialMode.requireSettled()
    private var readingAnchor: ConversationScrollAnchor? = null
    private val commandLifetime = StalenessGuard()
    private val intentLifetime = StalenessGuard()
    private var activeCommand: Job? = null
    private var userGestureInProgress = false
    private val foregroundRestoreLifetime = StalenessGuard()
    private var foregroundSnapshot by mutableStateOf<ConversationForegroundSnapshot?>(null)

    var mode by mutableStateOf(settledMode)
        private set

    var foregroundRestoreInProgress by mutableStateOf(false)
        private set

    val isFollowingTail: Boolean
        get() = settledMode is ConversationScrollMode.FollowingTail

    /**
     * True for a tail follower, including one whose only in-flight command is a passive tail correction — a
     * target-less jump that keeps the newest edge pinned for a new row, a reaction, a viewport or an IME change.
     * Explicit navigation in flight still reads as not following.
     */
    val isFollowingTailThroughPassiveCorrection: Boolean
        get() =
            isFollowingTail &&
                when (val current = mode) {
                    ConversationScrollMode.FollowingTail -> true
                    is ConversationScrollMode.ProgrammaticJump ->
                        current.targetMessageId == null && current.reason in PASSIVE_TAIL_CORRECTIONS
                    else -> false
                }

    /** Captures the current durable navigation intent for deferred restoration. */
    val intentToken: ConversationScrollIntentToken
        get() = ConversationScrollIntentToken(intentLifetime.capture())

    /** Records the stable reading intent independently from transient list geometry. */
    fun bookmark(anchor: ConversationScrollAnchor): ConversationScrollBookmark {
        val stableAnchor =
            if (settledMode is ConversationScrollMode.ReadingHistory) {
                readingAnchor ?: anchor.also { readingAnchor = it }
            } else {
                anchor
            }
        return ConversationScrollBookmark(
            anchor = stableAnchor,
            settledMode = settledMode,
            intentRevision = intentLifetime.capture(),
        )
    }

    /** Opens a latest-wins foreground correction transaction for [snapshot]. */
    fun beginForegroundRestore(snapshot: ConversationForegroundSnapshot): ConversationForegroundRestoreToken {
        invalidateActiveCommand()
        mode = settledMode
        val restoreRevision = foregroundRestoreLifetime.advance()
        foregroundSnapshot = snapshot
        foregroundRestoreInProgress = true
        return ConversationForegroundRestoreToken(
            revision = restoreRevision,
            expectedImeVisible = snapshot.geometry.imeBottomPx > 0,
        )
    }

    /** Applies a resumed layout correction only while [token] remains current. */
    suspend fun completeForegroundRestore(
        token: ConversationForegroundRestoreToken,
        resumedGeometry: ConversationForegroundGeometry,
        resumedTimelineStructure: ConversationTimelineStructure =
            foregroundSnapshot?.timelineStructure ?: ConversationTimelineStructure(emptyList(), 0),
        resumedScrollAnchor: ConversationScrollAnchor? = null,
        resolveAnchorIndex: (ConversationScrollAnchor) -> Int?,
        resolveTailIndex: () -> Int,
    ): Boolean {
        val snapshot = foregroundSnapshot
        return if (snapshot == null || !foregroundRestoreLifetime.isCurrent(token.revision)) {
            false
        } else {
            val presentationChanged =
                snapshot.geometry != resumedGeometry ||
                    snapshot.timelineStructure != resumedTimelineStructure ||
                    (resumedScrollAnchor != null && resumedScrollAnchor != snapshot.scrollBookmark.anchor)
            if (!presentationChanged) {
                clearForegroundRestore(token)
                true
            } else {
                try {
                    correctForegroundPresentation(
                        snapshot = snapshot,
                        resolveAnchorIndex = resolveAnchorIndex,
                        resolveTailIndex = resolveTailIndex,
                    )
                } finally {
                    clearForegroundRestore(token)
                }
            }
        }
    }

    /** Restores either tail-following or the durable reading anchor captured before backgrounding. */
    private suspend fun correctForegroundPresentation(
        snapshot: ConversationForegroundSnapshot,
        resolveAnchorIndex: (ConversationScrollAnchor) -> Int?,
        resolveTailIndex: () -> Int,
    ): Boolean =
        when (snapshot.scrollBookmark.settledMode) {
            ConversationScrollMode.FollowingTail ->
                runCommand(
                    transientMode =
                        ConversationScrollMode.ProgrammaticJump(
                            targetMessageId = null,
                            reason = ConversationScrollReason.LifecycleResume,
                        ),
                    resultingMode = ConversationScrollMode.FollowingTail,
                    preserveForegroundRestore = true,
                ) {
                    scrollToTail(resolveTailIndex())
                }
            is ConversationScrollMode.ReadingHistory -> {
                val bookmark = snapshot.scrollBookmark
                if (!intentLifetime.isCurrent(bookmark.intentRevision)) {
                    false
                } else {
                    val anchor = bookmark.anchor
                    readingAnchor = anchor
                    runCommand(
                        transientMode = ConversationScrollMode.Restoring(anchor.messageId, anchor.pixelOffset),
                        resultingMode = bookmark.settledMode,
                        preserveForegroundRestore = true,
                    ) {
                        scrollToItem(resolveAnchorIndex(anchor) ?: anchor.listIndex, anchor.pixelOffset)
                    }
                }
            }
            else -> false
        }

    /** Discards the captured foreground snapshot and invalidates deferred correction. */
    fun cancelForegroundRestore() {
        foregroundRestoreLifetime.advance()
        foregroundSnapshot = null
        foregroundRestoreInProgress = false
    }

    /** Retires only this resume's snapshot; a late handoff cannot cancel its replacement. */
    fun cancelForegroundRestore(token: ConversationForegroundRestoreToken) {
        if (!foregroundRestoreLifetime.isCurrent(token.revision)) return
        cancelForegroundRestore()
    }

    /** Typing/focus/navigation also retire an already-started deferred lifecycle writer. */
    fun cancelForegroundRestoreForUserIntent() {
        if (!foregroundRestorePending) return
        cancelForegroundRestore()
        invalidateActiveCommand()
        mode = settledMode
    }

    /** Whether an anchor correction still owns its captured snapshot after live drawing resumes. */
    internal val foregroundRestorePending: Boolean
        get() = foregroundSnapshot != null

    /**
     * Opens the freshness draw gate while keeping the
     * captured snapshot, so the next settled geometry can still apply the one
     * deferred correction. User intent, navigation, and disposal discard the
     * retained snapshot through the existing cancellation paths.
     */
    fun releaseForegroundRestoreGate(token: ConversationForegroundRestoreToken) {
        if (!foregroundRestoreLifetime.isCurrent(token.revision)) return
        foregroundRestoreInProgress = false
    }

    /** Closes the matching restore without clearing a transaction that replaced it. */
    private fun clearForegroundRestore(token: ConversationForegroundRestoreToken) {
        if (!foregroundRestoreLifetime.isCurrent(token.revision)) return
        foregroundSnapshot = null
        foregroundRestoreInProgress = false
    }

    /** Makes a user drag the newest scroll intent and cancels deferred lifecycle correction. */
    fun onUserGestureStarted(anchor: ConversationScrollAnchor) {
        cancelForegroundRestore()
        invalidateActiveCommand()
        userGestureInProgress = true
        readingAnchor = anchor
        setSettledMode(
            ConversationScrollMode.ReadingHistory(
                anchorMessageId = anchor.messageId,
                pixelOffset = anchor.pixelOffset,
            ),
            forceRevision = true,
        )
    }

    /**
     * Commits the durable tail or history intent selected by a completed user gesture.
     *
     * The gesture now settles only once its fling has come to rest, so a command the reader
     * started while the transcript was still coasting, a send, the jump button or a reply quote,
     * may already own the list. That command supersedes the gesture: it settles its own mode when
     * it completes, and cancelling it here would discard the reader's later, deliberate action.
     * Where the gesture ended is still recorded as the durable intent, so a command that fails
     * falls back to it rather than to the anchor captured when the finger first landed, but the
     * command's transient mode is left untouched so nothing re-anchors underneath it.
     */
    fun onUserGestureSettled(
        anchor: ConversationScrollAnchor,
        nearBottom: Boolean,
    ) {
        userGestureInProgress = false
        val settled =
            if (nearBottom) {
                ConversationScrollMode.FollowingTail
            } else {
                ConversationScrollMode.ReadingHistory(anchor.messageId, anchor.pixelOffset)
            }
        readingAnchor = anchor.takeUnless { nearBottom }
        if (activeCommand != null) {
            intentLifetime.advance()
            settledMode = settled
            return
        }
        invalidateActiveCommand()
        setSettledMode(settled, forceRevision = true)
    }

    /**
     * Commits the durable intent of a finished navigation landing.
     *
     * A landing that rests at the real newest edge resumes tail following, so a message arriving next is revealed
     * instead of hiding behind the composer and a later leave saves no history snapshot. Any other landing keeps
     * its logical history anchor through [settleReadingAt], including one that reached its reading start while part
     * of the tail is still clipped. [atNewestEdge] is the caller's verdict from the native newest-edge clamp, not the
     * tolerant near-bottom reading that gestures use, and a window that still has newer pages never qualifies.
     */
    fun settleLandingAt(
        anchor: ConversationScrollAnchor,
        atNewestEdge: Boolean,
    ) {
        if (!atNewestEdge) {
            settleReadingAt(anchor)
            return
        }
        invalidateActiveCommand()
        userGestureInProgress = false
        setSettledMode(ConversationScrollMode.FollowingTail, forceRevision = true)
    }

    /** Replaces transient command ownership with a durable logical history anchor. */
    fun settleReadingAt(anchor: ConversationScrollAnchor) {
        invalidateActiveCommand()
        userGestureInProgress = false
        readingAnchor = anchor
        setSettledMode(
            ConversationScrollMode.ReadingHistory(anchor.messageId, anchor.pixelOffset),
            forceRevision = true,
        )
    }

    /** Reapplies the current history bookmark only while no newer gesture or command owns it. */
    suspend fun reanchorReadingHistory(resolveAnchorIndex: (ConversationScrollAnchor) -> Int?): Boolean {
        if (foregroundRestorePending) return false
        val anchor = readingAnchor
        val canRestore =
            !userGestureInProgress &&
                settledMode is ConversationScrollMode.ReadingHistory &&
                mode is ConversationScrollMode.ReadingHistory
        return if (anchor == null || !canRestore) {
            false
        } else {
            runCommand(
                transientMode = ConversationScrollMode.Restoring(anchor.messageId, anchor.pixelOffset),
                resultingMode = settledMode,
            ) {
                scrollToItem(resolveAnchorIndex(anchor) ?: anchor.listIndex, anchor.pixelOffset)
            }
        }
    }

    /** Restores a bookmark only when its durable intent has not been superseded. */
    suspend fun restoreBookmark(
        bookmark: ConversationScrollBookmark,
        expectedIntent: ConversationScrollIntentToken = ConversationScrollIntentToken(bookmark.intentRevision),
        resolveAnchorIndex: (ConversationScrollAnchor) -> Int?,
    ): Boolean {
        if (!intentLifetime.isCurrent(expectedIntent.revision)) return false
        val anchor = bookmark.anchor
        readingAnchor = anchor.takeIf { bookmark.settledMode is ConversationScrollMode.ReadingHistory }
        return runCommand(
            transientMode = ConversationScrollMode.Restoring(anchor.messageId, anchor.pixelOffset),
            resultingMode = bookmark.settledMode,
        ) {
            scrollToItem(resolveAnchorIndex(anchor) ?: anchor.listIndex, anchor.pixelOffset)
        }
    }

    /**
     * Repositions only while the settled user intent still follows the tail.
     * A zero [frameCount] consumes geometry already measured by the caller;
     * positive counts preserve the bounded post-layout frame chase.
     */
    suspend fun followTailIfAllowed(
        resolveTailIndex: () -> Int,
        reason: ConversationScrollReason,
        frameCount: Int = 1,
        awaitFrame: suspend () -> Unit = { withFrameNanos { } },
    ): Boolean {
        if (!isFollowingTail || mode !is ConversationScrollMode.FollowingTail) return false
        return programmaticJump(
            targetMessageId = null,
            reason = reason,
            resultingMode = ConversationScrollMode.FollowingTail,
        ) {
            if (frameCount <= 0) {
                scrollToTail(resolveTailIndex())
            } else {
                repeat(frameCount) {
                    awaitFrame()
                    scrollToTail(resolveTailIndex())
                }
            }
        }
    }

    /**
     * Keeps the physical tail pinned while its row or viewport is remeasured.
     *
     * Reaction projections can arrive before their chip's final Compose
     * measurement. A single next-frame scroll can therefore use the old list
     * extent. Accepted sends can likewise clear dictation/composer chrome only
     * after the optimistic row appears. This bounded settle follows the tail
     * until the measured row and viewport geometry remain stable, then applies
     * one final correction. The coordinator's normal command ownership makes
     * the chase cancellable by a drag, navigation, conversation replacement,
     * or disposal.
     */
    suspend fun settleTailAfterLayoutChange(
        resolveTailIndex: () -> Int,
        captureLayout: () -> ConversationTailLayout,
        reason: ConversationScrollReason = ConversationScrollReason.ReactionLayout,
        maxSettleFrames: Int = DEFAULT_TAIL_LAYOUT_SETTLE_FRAMES,
        minimumSettleFrames: Int = MIN_TAIL_LAYOUT_SETTLE_FRAMES,
        settleTrigger: ConversationTailSettleTrigger = ConversationTailSettleTrigger.RowHeight,
        requireTailClearance: Boolean = false,
        awaitFrame: suspend () -> Unit = { withFrameNanos { } },
    ): Boolean {
        if (!isFollowingTail || mode !is ConversationScrollMode.FollowingTail) return false
        return programmaticJump(
            targetMessageId = null,
            reason = reason,
            resultingMode = ConversationScrollMode.FollowingTail,
        ) {
            var previousLayout: ConversationTailLayout? = null
            var previousTailIndex: Int? = null
            val baselineLayout = captureLayout()
            var baselineRowHeightPx = baselineLayout.lastRowHeightPx
            val baselineBottomGeometry = baselineLayout.bottomGeometry
            var observedSettleTrigger = false
            var stableFrames = 0
            var frame = 0
            scrollToTail(resolveTailIndex())
            while (frame < maxSettleFrames.coerceAtLeast(1)) {
                awaitFrame()
                val currentLayout = captureLayout()
                val currentRowHeightPx = currentLayout.lastRowHeightPx
                if (baselineRowHeightPx == null) {
                    baselineRowHeightPx = currentRowHeightPx
                }
                if (
                    currentLayout.observesSettleTrigger(
                        trigger = settleTrigger,
                        baselineRowHeightPx = baselineRowHeightPx,
                        baselineBottomGeometry = baselineBottomGeometry,
                    )
                ) {
                    observedSettleTrigger = true
                }
                stableFrames =
                    if (currentLayout.isStableAfter(previousLayout, settleTrigger)) {
                        stableFrames + 1
                    } else {
                        0
                    }
                val tailIndex = resolveTailIndex()
                // Preserve the first key-anchor reset and every uncertain or changing measurement.
                // A repeated correction cannot improve the same measured, already-clear tail.
                if (currentLayout.needsTailCorrection(previousLayout, tailIndex, previousTailIndex)) {
                    scrollToTail(tailIndex)
                }
                previousLayout = currentLayout
                previousTailIndex = tailIndex
                frame++
                if (
                    currentLayout.canFinishSettle(
                        observedSettleTrigger = observedSettleTrigger,
                        frame = frame,
                        minimumSettleFrames = minimumSettleFrames,
                        stableFrames = stableFrames,
                        requireTailClearance = requireTailClearance,
                    )
                ) {
                    break
                }
            }
        }
    }

    /**
     * Re-issues the tail scroll while the tail is unmeasured or still under the composer inset, or whenever the
     * layout or tail index changed since the last frame. An identical, already-clear measurement needs none.
     */
    private fun ConversationTailLayout.needsTailCorrection(
        previous: ConversationTailLayout?,
        tailIndex: Int,
        previousTailIndex: Int?,
    ): Boolean {
        if (measuredTailKey == null || !tailClearsViewportStart) return true
        return this != previous || tailIndex != previousTailIndex
    }

    /** Preserves reaction geometry; an unready layout or absent prior frame is not stable. */
    private fun ConversationTailLayout.isStableAfter(
        previous: ConversationTailLayout?,
        trigger: ConversationTailSettleTrigger,
    ): Boolean {
        if (!isReady || previous == null) return false
        return when (trigger) {
            ConversationTailSettleTrigger.RowHeight ->
                lastRowHeightPx == previous.lastRowHeightPx &&
                    tailOffsetPx == previous.tailOffsetPx &&
                    tailSizePx == previous.tailSizePx &&
                    viewportEndOffsetPx == previous.viewportEndOffsetPx
            ConversationTailSettleTrigger.BottomGeometry -> this == previous
        }
    }

    /** Separates trigger-specific change detection from the bounded settle loop. */
    private fun ConversationTailLayout.observesSettleTrigger(
        trigger: ConversationTailSettleTrigger,
        baselineRowHeightPx: Int?,
        baselineBottomGeometry: ConversationTailBottomGeometry,
    ): Boolean =
        when (trigger) {
            ConversationTailSettleTrigger.RowHeight ->
                lastRowHeightPx != null && baselineRowHeightPx != null && lastRowHeightPx != baselineRowHeightPx
            ConversationTailSettleTrigger.BottomGeometry -> bottomGeometry != baselineBottomGeometry
        }

    /** Requires the trigger, stable geometry, frame budget and optional composer clearance together. */
    private fun ConversationTailLayout.canFinishSettle(
        observedSettleTrigger: Boolean,
        frame: Int,
        minimumSettleFrames: Int,
        stableFrames: Int,
        requireTailClearance: Boolean,
    ): Boolean =
        observedSettleTrigger &&
            frame >= minimumSettleFrames.coerceAtLeast(1) &&
            stableFrames >= REQUIRED_STABLE_TAIL_LAYOUT_FRAMES &&
            (!requireTailClearance || tailClearsViewportStart)

    /** Runs an explicit jump under the shared latest-wins scroll-command fence. */
    suspend fun programmaticJump(
        targetMessageId: String?,
        reason: ConversationScrollReason,
        resultingMode: ConversationScrollMode? = null,
        operation: suspend ConversationScrollCommandScope.() -> Unit,
    ): Boolean {
        if (reason.supersedesUnreadJump) onExplicitNavigation()
        if (foregroundRestorePending && reason.defersDuringForegroundRestore) return false
        return runCommand(
            transientMode = ConversationScrollMode.ProgrammaticJump(targetMessageId, reason),
            resultingMode = resultingMode ?: settledMode,
            operation = operation,
        )
    }

    /** Runs one scroll writer command and settles state only if it remains newest. */
    private suspend fun runCommand(
        transientMode: ConversationScrollMode,
        resultingMode: ConversationScrollMode,
        preserveForegroundRestore: Boolean = false,
        operation: suspend ConversationScrollCommandScope.() -> Unit,
    ): Boolean {
        if (!preserveForegroundRestore) cancelForegroundRestore()
        return supervisorScope {
            val previous = activeCommand
            val serial = commandLifetime.advance()
            val command =
                async(start = CoroutineStart.LAZY) {
                    val commandScope = ConversationScrollCommandScope(serial)
                    commandScope.operation()
                }
            previous?.cancel()
            activeCommand = command
            mode = transientMode
            command.start()
            try {
                command.await()
                if (!commandLifetime.isCurrent(serial)) {
                    false
                } else {
                    setSettledMode(resultingMode.requireSettled())
                    true
                }
            } catch (_: CancellationException) {
                // A superseded command is expected to return false without
                // cancelling its long-lived producer (for example snapshotFlow).
                // Preserve normal cancellation when the producer itself is gone.
                currentCoroutineContext().ensureActive()
                false
            } finally {
                if (commandLifetime.isCurrent(serial)) {
                    activeCommand = null
                    mode = settledMode
                }
            }
        }
    }

    /** Makes the active writer command stale before cancelling its coroutine. */
    private fun invalidateActiveCommand() {
        commandLifetime.advance()
        activeCommand?.cancel()
        activeCommand = null
    }

    /** Publishes a stable mode and advances durable intent when it changes. */
    private fun setSettledMode(
        newMode: ConversationScrollMode,
        forceRevision: Boolean = false,
    ) {
        val settled = newMode.requireSettled()
        if (forceRevision || settled != settledMode) intentLifetime.advance()
        settledMode = settled
        mode = settled
        if (settled is ConversationScrollMode.FollowingTail) readingAnchor = null
    }

    internal inner class ConversationScrollCommandScope internal constructor(
        private val serial: Long,
    ) {
        /** The newest row currently laid out; the reversed list puts it nearest the composer. */
        val firstVisibleItemIndex: Int
            get() = writer.firstVisibleItemIndex

        suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int = 0,
        ) {
            ensureCurrent()
            writer.scrollToItem(index.coerceAtLeast(0), scrollOffset)
        }

        /** Scrolls through the final row so an oversized message reaches its physical end. */
        suspend fun scrollToTail(index: Int) {
            ensureCurrent()
            writer.scrollToTail(index.coerceAtLeast(0))
        }

        /** Corrects a reached row so its reading start meets the top of the viewport. */
        suspend fun alignReadingStart(index: Int) {
            ensureCurrent()
            writer.alignReadingStart(index.coerceAtLeast(0))
        }

        /**
         * Keeps distance-independent navigation responsive: snap near a far target, then animate only
         * the final few rows. Message-backed callers can re-resolve after each snap because paging may
         * insert or remove list headers while the command is suspended.
         * [resolveDestination] reads geometry after prepositioning so a later settle pass can use
         * the actual approach coordinate instead of an obsolete measurement. It returns the
         * (index, scroll offset) write for the resolved target, which may name a neighbouring row.
         */
        suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int = 0,
            traceMentionJump: Boolean = false,
            resolveDestination: (Int) -> Pair<Int, Int> = { it to scrollOffset },
            resolveIndex: () -> Int? = { index },
        ): Boolean {
            ensureCurrent()
            var targetIndex = resolveIndex()?.coerceAtLeast(0)
            var repositionAttempts = 0
            while (
                targetIndex != null &&
                repositionAttempts < MAX_TARGET_REPOSITION_ATTEMPTS &&
                prePositionIfFar(targetIndex, traceMentionJump)
            ) {
                repositionAttempts++
                ensureCurrent()
                targetIndex = resolveIndex()?.coerceAtLeast(0)
            }
            val resolvedTargetIndex = targetIndex ?: return false
            // Prepositioning can suspend through a keyboard/header/row measurement change.
            // Use the geometry now available, not an offset captured before that handoff.
            val (destinationIndex, destinationOffset) = resolveDestination(resolvedTargetIndex)
            if (isFar(resolvedTargetIndex)) {
                traceMentionWrite(traceMentionJump, ConversationMentionJumpTrace.POSITION) {
                    writer.scrollToItem(destinationIndex, destinationOffset)
                }
            } else {
                traceMentionWrite(traceMentionJump, ConversationMentionJumpTrace.ANIMATION) {
                    writer.animateScrollToItem(destinationIndex, destinationOffset)
                }
            }
            ensureCurrent()
            return true
        }

        /**
         * Reaches the newest row: animated when it is already within a few rows, in one snap when it
         * is not.
         *
         * A far tail is the jump out of deep history, usually just after the bounded window was
         * replaced with its newest rows. Snapping near and animating the last ten rows — what
         * [animateScrollToItem] does for a reply target — composed and measured every row the
         * animation passed at several rows per frame, which was the only paging journey a reader could
         * feel (three consecutive 100 ms frames). The reader asked for the newest message, not a tour
         * of the rows above it, so a far jump lands in a single write.
         */
        suspend fun animateScrollToTail(
            index: Int,
            resolveIndex: () -> Int? = { index },
        ): Boolean {
            ensureCurrent()
            val targetIndex = resolveIndex()?.coerceAtLeast(0) ?: return false
            if (isFar(targetIndex)) {
                writer.scrollToTail(targetIndex)
                // The snap suspends, and a page landing meanwhile can add or drop the structural rows
                // below the newest message, moving its index. Re-read it once and correct with a
                // second snap; still no animation.
                ensureCurrent()
                val settledIndex = resolveIndex()?.coerceAtLeast(0)
                if (settledIndex != null && settledIndex != targetIndex) writer.scrollToTail(settledIndex)
            } else {
                writer.animateScrollToTail(targetIndex)
            }
            return true
        }

        private suspend fun prePositionIfFar(
            targetIndex: Int,
            traceMentionJump: Boolean,
        ): Boolean {
            val currentIndex = writer.firstVisibleItemIndex
            if (!isFar(targetIndex)) return false
            val approachIndex =
                if (targetIndex > currentIndex) {
                    targetIndex - MAX_ANIMATED_SCROLL_ITEMS
                } else {
                    targetIndex + MAX_ANIMATED_SCROLL_ITEMS
                }
            traceMentionWrite(traceMentionJump, ConversationMentionJumpTrace.POSITION) {
                writer.scrollToItem(approachIndex, 0)
            }
            return true
        }

        /** Only mention navigation opts in; other scroll paths retain their existing trace budget. */
        private suspend inline fun traceMentionWrite(
            enabled: Boolean,
            name: String,
            operation: () -> Unit,
        ) {
            if (enabled) tracedPagingSection(name, operation) else operation()
        }

        private fun isFar(targetIndex: Int): Boolean {
            val indexDistance = abs(targetIndex - writer.firstVisibleItemIndex)
            return indexDistance > MAX_ANIMATED_SCROLL_ITEMS
        }

        /** Rejects writer work after another command has claimed ownership. */
        private suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (!commandLifetime.isCurrent(serial)) {
                throw CancellationException("Superseded conversation scroll command")
            }
        }
    }
}

private val ConversationScrollReason.supersedesUnreadJump: Boolean
    get() =
        when (this) {
            ConversationScrollReason.Reply,
            ConversationScrollReason.Mention,
            ConversationScrollReason.Search,
            ConversationScrollReason.FocusMessage,
            ConversationScrollReason.MessageExpansion,
            ConversationScrollReason.ReadAloudFollow,
            ConversationScrollReason.JumpToNewest,
            ConversationScrollReason.Send,
            -> true
            ConversationScrollReason.InitialAnchor,
            ConversationScrollReason.NotificationTarget,
            ConversationScrollReason.SavedRestore,
            ConversationScrollReason.LifecycleResume,
            ConversationScrollReason.ImeTransition,
            ConversationScrollReason.ViewportChange,
            ConversationScrollReason.NewMessage,
            ConversationScrollReason.ReactionLayout,
            ConversationScrollReason.BottomInput,
            ConversationScrollReason.UnreadStart,
            ConversationScrollReason.UnreadTail,
            -> false
        }

/** Rejects transient command modes where a durable post-command intent is required. */
private fun ConversationScrollMode.requireSettled(): ConversationScrollMode {
    require(this is ConversationScrollMode.FollowingTail || this is ConversationScrollMode.ReadingHistory) {
        "Transient scroll mode cannot be persisted: $this"
    }
    return this
}

private val ConversationScrollReason.defersDuringForegroundRestore: Boolean
    get() =
        when (this) {
            ConversationScrollReason.ImeTransition,
            ConversationScrollReason.ViewportChange,
            ConversationScrollReason.NewMessage,
            ConversationScrollReason.ReactionLayout,
            ConversationScrollReason.BottomInput,
            -> true
            else -> false
        }
