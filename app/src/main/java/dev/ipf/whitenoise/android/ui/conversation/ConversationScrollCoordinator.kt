package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.CancellationException
import kotlin.math.abs

private const val MAX_ANIMATED_SCROLL_ITEMS = 10
private const val MAX_TARGET_REPOSITION_ATTEMPTS = 3
private const val DEFAULT_TAIL_LAYOUT_SETTLE_FRAMES = 8
private const val MIN_TAIL_LAYOUT_SETTLE_FRAMES = 4
private const val REQUIRED_STABLE_TAIL_LAYOUT_FRAMES = 2

/** The durable reading intent behind the conversation's transient list geometry. */
internal sealed interface ConversationScrollMode {
    data object FollowingTail : ConversationScrollMode

    data class ReadingHistory(
        val anchorMessageId: String?,
        val pixelOffset: Int,
    ) : ConversationScrollMode

    data class Restoring(
        val anchorMessageId: String?,
        val pixelOffset: Int,
    ) : ConversationScrollMode

    data class ProgrammaticJump(
        val targetMessageId: String?,
        val reason: ConversationScrollReason,
    ) : ConversationScrollMode
}

internal enum class ConversationScrollReason {
    InitialAnchor,
    SavedRestore,
    LifecycleResume,
    ImeTransition,
    ViewportChange,
    NewMessage,
    ReactionLayout,
    BottomInput,
    Reply,
    Mention,
    Search,
    FocusMessage,
    ReadAloudFollow,
    UnreadStart,
    UnreadTail,
    JumpToNewest,
    Send,
}

internal enum class ConversationJumpToNewestOutcome {
    UnreadStart,
    Tail,
    Cancelled,
}

/** Stable logical anchor; [listIndex] is only the fallback when the ids are gone. */
internal data class ConversationScrollAnchor(
    val listIndex: Int,
    val pixelOffset: Int,
    val itemId: String?,
    val messageId: String?,
)

internal fun conversationScrollAnchor(
    listState: LazyListState,
    renderedItemIds: List<String>,
    renderedMessageIds: List<String>,
    hasOlderHeader: Boolean,
    hasInlineTopError: Boolean = false,
): ConversationScrollAnchor {
    val firstTimelineListIndex =
        1 +
            (if (hasInlineTopError) 1 else 0) +
            (if (hasOlderHeader) 1 else 0)
    val visibleTimelineRow =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { visible ->
            val timelineIndex = visible.index - firstTimelineListIndex
            timelineIndex in renderedItemIds.indices && timelineIndex in renderedMessageIds.indices
        }
    if (visibleTimelineRow != null) {
        val timelineIndex = visibleTimelineRow.index - firstTimelineListIndex
        return ConversationScrollAnchor(
            listIndex = visibleTimelineRow.index,
            pixelOffset = -visibleTimelineRow.offset,
            itemId = renderedItemIds[timelineIndex],
            messageId = renderedMessageIds[timelineIndex],
        )
    }
    return ConversationScrollAnchor(
        listIndex = listState.firstVisibleItemIndex,
        pixelOffset = listState.firstVisibleItemScrollOffset,
        itemId = null,
        messageId = null,
    )
}

internal data class ConversationScrollBookmark(
    val anchor: ConversationScrollAnchor,
    val settledMode: ConversationScrollMode,
    internal val intentRevision: Long,
)

/** Stable layout inputs that determine the transcript's visible viewport. */
internal data class ConversationForegroundGeometry(
    val viewportHeightPx: Int,
    val imeBottomPx: Int,
    val bottomChromeHeightPx: Int,
)

internal data class ConversationForegroundSnapshot(
    val scrollBookmark: ConversationScrollBookmark,
    val geometry: ConversationForegroundGeometry,
    val timelineStructure: ConversationTimelineStructure = ConversationTimelineStructure(emptyList(), 0),
)

internal class ConversationForegroundRestoreToken internal constructor(
    internal val revision: Long,
    internal val expectedImeVisible: Boolean,
)

internal data class ConversationTimelineStructure(
    val rowKeys: List<Pair<String, String>>,
    val olderHeaderCount: Int,
    val inlineTopErrorCount: Int = 0,
)

internal data class ConversationInitialAnchorLayout(
    val viewportHeight: Int,
    val targetItemSize: Int?,
) {
    val isReady: Boolean
        get() = viewportHeight > 0 && targetItemSize != null
}

/** Measured tail geometry used to settle same-row height changes. */
internal data class ConversationTailLayout(
    val lastRowHeightPx: Int?,
    val tailOffsetPx: Int?,
    val tailSizePx: Int?,
    val viewportEndOffsetPx: Int,
) {
    val isReady: Boolean
        get() =
            lastRowHeightPx != null &&
                tailOffsetPx != null &&
                tailSizePx != null &&
                viewportEndOffsetPx > 0
}

/**
 * Baselines layout state at the hidden initial anchor. Startup materialization
 * is not a post-open change and must not trigger another visible scroll.
 */
internal class ConversationPostInitialReanchorGate {
    private var structure: ConversationTimelineStructure? = null
    private var viewportHeight: Int? = null

    fun commit(
        structure: ConversationTimelineStructure,
        viewportHeight: Int,
    ) {
        this.structure = structure
        this.viewportHeight = viewportHeight
    }

    fun onStructure(structure: ConversationTimelineStructure): Boolean {
        val previous = this.structure ?: return false
        this.structure = structure
        return structure != previous
    }

    fun onViewportHeight(viewportHeight: Int): Boolean {
        val previous = this.viewportHeight ?: return false
        this.viewportHeight = viewportHeight
        return viewportHeight != previous
    }
}

internal class ConversationScrollIntentToken internal constructor(
    internal val revision: Long,
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
    private var foregroundSnapshot: ConversationForegroundSnapshot? = null

    var mode by mutableStateOf(settledMode)
        private set

    var foregroundRestoreInProgress by mutableStateOf(false)
        private set

    val isFollowingTail: Boolean
        get() = settledMode is ConversationScrollMode.FollowingTail

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

    /**
     * Opens the draw gate after the bounded settle deadline while keeping the
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

    fun onUserGestureSettled(
        anchor: ConversationScrollAnchor,
        nearBottom: Boolean,
    ) {
        invalidateActiveCommand()
        userGestureInProgress = false
        if (nearBottom) {
            readingAnchor = null
            setSettledMode(ConversationScrollMode.FollowingTail, forceRevision = true)
        } else {
            readingAnchor = anchor
            setSettledMode(
                ConversationScrollMode.ReadingHistory(anchor.messageId, anchor.pixelOffset),
                forceRevision = true,
            )
        }
    }

    fun settleReadingAt(anchor: ConversationScrollAnchor) {
        invalidateActiveCommand()
        userGestureInProgress = false
        readingAnchor = anchor
        setSettledMode(
            ConversationScrollMode.ReadingHistory(anchor.messageId, anchor.pixelOffset),
            forceRevision = true,
        )
    }

    suspend fun reanchorReadingHistory(resolveAnchorIndex: (ConversationScrollAnchor) -> Int?): Boolean {
        if (foregroundRestoreInProgress) return false
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
     * Keeps the physical tail pinned while an existing last row is remeasured.
     *
     * Reaction projections can arrive before their chip's final Compose
     * measurement. A single next-frame scroll can therefore use the old list
     * extent. This bounded settle follows the tail until the measured row and
     * viewport geometry remain stable, then applies one final correction. The
     * coordinator's normal command ownership makes the chase cancellable by a
     * drag, navigation, conversation replacement, or disposal.
     */
    suspend fun settleTailAfterLayoutChange(
        resolveTailIndex: () -> Int,
        captureLayout: () -> ConversationTailLayout,
        reason: ConversationScrollReason = ConversationScrollReason.ReactionLayout,
        maxSettleFrames: Int = DEFAULT_TAIL_LAYOUT_SETTLE_FRAMES,
        awaitFrame: suspend () -> Unit = { withFrameNanos { } },
    ): Boolean {
        if (!isFollowingTail || mode !is ConversationScrollMode.FollowingTail) return false
        return programmaticJump(
            targetMessageId = null,
            reason = reason,
            resultingMode = ConversationScrollMode.FollowingTail,
        ) {
            var previousLayout: ConversationTailLayout? = null
            var baselineRowHeightPx = captureLayout().lastRowHeightPx
            var observedRowHeightChange = false
            var stableFrames = 0
            var frame = 0
            scrollToTail(resolveTailIndex())
            while (frame < maxSettleFrames.coerceAtLeast(1)) {
                awaitFrame()
                val currentLayout = captureLayout()
                val currentRowHeightPx = currentLayout.lastRowHeightPx
                if (baselineRowHeightPx == null) {
                    baselineRowHeightPx = currentRowHeightPx
                } else if (currentRowHeightPx != null && currentRowHeightPx != baselineRowHeightPx) {
                    observedRowHeightChange = true
                }
                stableFrames =
                    if (currentLayout.isReady && currentLayout == previousLayout) {
                        stableFrames + 1
                    } else {
                        0
                    }
                previousLayout = currentLayout
                scrollToTail(resolveTailIndex())
                frame++
                if (
                    observedRowHeightChange &&
                    frame >= MIN_TAIL_LAYOUT_SETTLE_FRAMES &&
                    stableFrames >= REQUIRED_STABLE_TAIL_LAYOUT_FRAMES
                ) {
                    break
                }
            }
        }
    }

    /** Runs an explicit jump under the shared latest-wins scroll-command fence. */
    suspend fun programmaticJump(
        targetMessageId: String?,
        reason: ConversationScrollReason,
        resultingMode: ConversationScrollMode? = null,
        operation: suspend ConversationScrollCommandScope.() -> Unit,
    ): Boolean {
        if (reason.supersedesUnreadJump) onExplicitNavigation()
        if (foregroundRestoreInProgress && reason.defersDuringForegroundRestore) return false
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
                    ConversationScrollCommandScope(serial).operation()
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

        /**
         * Keeps distance-independent navigation responsive: snap near a far target, then animate only
         * the final few rows. Message-backed callers can re-resolve after each snap because paging may
         * insert or remove list headers while the command is suspended.
         */
        suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int = 0,
            resolveIndex: () -> Int? = { index },
        ): Boolean {
            ensureCurrent()
            var targetIndex = resolveIndex()?.coerceAtLeast(0)
            var repositionAttempts = 0
            while (
                targetIndex != null &&
                repositionAttempts < MAX_TARGET_REPOSITION_ATTEMPTS &&
                prePositionIfFar(targetIndex)
            ) {
                repositionAttempts++
                ensureCurrent()
                targetIndex = resolveIndex()?.coerceAtLeast(0)
            }
            val resolvedTargetIndex = targetIndex ?: return false
            if (isFar(resolvedTargetIndex)) {
                writer.scrollToItem(resolvedTargetIndex, scrollOffset)
            } else {
                writer.animateScrollToItem(resolvedTargetIndex, scrollOffset)
            }
            return true
        }

        /** Animates to the final row and then its measured physical end. */
        suspend fun animateScrollToTail(
            index: Int,
            resolveIndex: () -> Int? = { index },
        ): Boolean {
            ensureCurrent()
            var targetIndex = resolveIndex()?.coerceAtLeast(0)
            var repositionAttempts = 0
            while (
                targetIndex != null &&
                repositionAttempts < MAX_TARGET_REPOSITION_ATTEMPTS &&
                prePositionIfFar(targetIndex)
            ) {
                repositionAttempts++
                ensureCurrent()
                targetIndex = resolveIndex()?.coerceAtLeast(0)
            }
            val resolvedTargetIndex = targetIndex ?: return false
            if (isFar(resolvedTargetIndex)) {
                writer.scrollToTail(resolvedTargetIndex)
            } else {
                writer.animateScrollToTail(resolvedTargetIndex)
            }
            return true
        }

        private suspend fun prePositionIfFar(targetIndex: Int): Boolean {
            val currentIndex = writer.firstVisibleItemIndex
            if (!isFar(targetIndex)) return false
            val approachIndex =
                if (targetIndex > currentIndex) {
                    targetIndex - MAX_ANIMATED_SCROLL_ITEMS
                } else {
                    targetIndex + MAX_ANIMATED_SCROLL_ITEMS
                }
            writer.scrollToItem(approachIndex, 0)
            return true
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

internal suspend fun ConversationScrollCoordinator.jumpToNewest(targetIndex: Int): Boolean =
    programmaticJump(
        targetMessageId = null,
        reason = ConversationScrollReason.JumpToNewest,
        resultingMode = ConversationScrollMode.FollowingTail,
    ) {
        animateScrollToTail(targetIndex)
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
                animateScrollToTail(resolveTailIndex())
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
                animateScrollToItem(initialTargetIndex, 0) {
                    resolveUnreadIndex()
                }
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

private val ConversationScrollReason.supersedesUnreadJump: Boolean
    get() =
        when (this) {
            ConversationScrollReason.Reply,
            ConversationScrollReason.Mention,
            ConversationScrollReason.Search,
            ConversationScrollReason.FocusMessage,
            ConversationScrollReason.ReadAloudFollow,
            ConversationScrollReason.JumpToNewest,
            ConversationScrollReason.Send,
            -> true
            ConversationScrollReason.InitialAnchor,
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

/**
 * Processes a newer drag immediately, cancelling any older Stop/Cancel waiter
 * that is still waiting for fling motion to finish.
 */
internal suspend fun Flow<Interaction>.collectConversationDragInteractions(
    onStarted: () -> Unit,
    awaitScrollSettled: suspend () -> Unit,
    onSettled: () -> Unit,
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
                awaitScrollSettled()
                onSettled()
            }
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

internal interface ConversationScrollWriter {
    val firstVisibleItemIndex: Int

    suspend fun scrollToItem(
        index: Int,
        scrollOffset: Int = 0,
    )

    suspend fun animateScrollToItem(
        index: Int,
        scrollOffset: Int = 0,
    )

    /** Reaches the physical end of [index], including when that item exceeds the viewport. */
    suspend fun scrollToTail(index: Int) {
        scrollToItem(index)
    }

    /** Animated counterpart of [scrollToTail]. */
    suspend fun animateScrollToTail(index: Int) {
        animateScrollToItem(index)
    }
}

/** The only implementation that writes the Compose list state. */
internal class LazyListConversationScrollWriter(
    private val listState: LazyListState,
) : ConversationScrollWriter {
    override val firstVisibleItemIndex: Int
        get() = listState.firstVisibleItemIndex

    override suspend fun scrollToItem(
        index: Int,
        scrollOffset: Int,
    ) {
        listState.scrollToItem(index, scrollOffset)
    }

    override suspend fun animateScrollToItem(
        index: Int,
        scrollOffset: Int,
    ) {
        listState.animateScrollToItem(index, scrollOffset)
    }

    override suspend fun scrollToTail(index: Int) {
        val visibleTailOffset = tailScrollOffset(index)
        if (visibleTailOffset > 0) {
            listState.scrollToItem(index, visibleTailOffset)
        } else {
            listState.scrollToItem(index)
            listState.scrollToItem(index, tailScrollOffset(index))
        }
    }

    override suspend fun animateScrollToTail(index: Int) {
        val visibleTailOffset = tailScrollOffset(index)
        if (visibleTailOffset > 0) {
            listState.animateScrollToItem(index, visibleTailOffset)
        } else {
            listState.animateScrollToItem(index)
            listState.animateScrollToItem(index, tailScrollOffset(index))
        }
    }

    /** Uses the just-measured row size as a clamped request for the content end. */
    private fun tailScrollOffset(index: Int): Int =
        listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
            ?.size
            ?: 0
}

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
