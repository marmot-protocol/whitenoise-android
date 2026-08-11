package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
    JumpToNewest,
    Send,
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
): ConversationScrollAnchor {
    val firstTimelineListIndex = 1 + (if (hasOlderHeader) 1 else 0)
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

internal data class ConversationTimelineStructure(
    val rowKeys: List<Pair<String, String>>,
    val olderHeaderCount: Int,
)

internal data class ConversationInitialAnchorLayout(
    val viewportHeight: Int,
    val targetItemSize: Int?,
) {
    val isReady: Boolean
        get() = viewportHeight > 0 && targetItemSize != null
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
internal class ConversationScrollCoordinator(
    private val writer: ConversationScrollWriter,
    initialMode: ConversationScrollMode = ConversationScrollMode.FollowingTail,
) {
    private var settledMode = initialMode.requireSettled()
    private var readingAnchor: ConversationScrollAnchor? = null
    private var commandSerial = 0L
    private var intentRevision = 0L
    private var activeCommand: Job? = null
    private var userGestureInProgress = false

    var mode by mutableStateOf(settledMode)
        private set

    val isFollowingTail: Boolean
        get() = settledMode is ConversationScrollMode.FollowingTail

    val intentToken: ConversationScrollIntentToken
        get() = ConversationScrollIntentToken(intentRevision)

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
            intentRevision = intentRevision,
        )
    }

    fun onUserGestureStarted(anchor: ConversationScrollAnchor) {
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

    suspend fun restoreBookmark(
        bookmark: ConversationScrollBookmark,
        expectedIntent: ConversationScrollIntentToken = ConversationScrollIntentToken(bookmark.intentRevision),
        resolveAnchorIndex: (ConversationScrollAnchor) -> Int?,
    ): Boolean {
        if (expectedIntent.revision != intentRevision) return false
        val anchor = bookmark.anchor
        readingAnchor = anchor.takeIf { bookmark.settledMode is ConversationScrollMode.ReadingHistory }
        return runCommand(
            transientMode = ConversationScrollMode.Restoring(anchor.messageId, anchor.pixelOffset),
            resultingMode = bookmark.settledMode,
        ) {
            scrollToItem(resolveAnchorIndex(anchor) ?: anchor.listIndex, anchor.pixelOffset)
        }
    }

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
            repeat(frameCount.coerceAtLeast(1)) {
                awaitFrame()
                scrollToItem(resolveTailIndex(), 0)
            }
        }
    }

    suspend fun programmaticJump(
        targetMessageId: String?,
        reason: ConversationScrollReason,
        resultingMode: ConversationScrollMode? = null,
        operation: suspend ConversationScrollCommandScope.() -> Unit,
    ): Boolean =
        runCommand(
            transientMode = ConversationScrollMode.ProgrammaticJump(targetMessageId, reason),
            resultingMode = resultingMode ?: settledMode,
            operation = operation,
        )

    private suspend fun runCommand(
        transientMode: ConversationScrollMode,
        resultingMode: ConversationScrollMode,
        operation: suspend ConversationScrollCommandScope.() -> Unit,
    ): Boolean =
        supervisorScope {
            val previous = activeCommand
            val serial = ++commandSerial
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
                if (serial != commandSerial) {
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
                if (serial == commandSerial) {
                    activeCommand = null
                    mode = settledMode
                }
            }
        }

    private fun invalidateActiveCommand() {
        commandSerial++
        activeCommand?.cancel()
        activeCommand = null
    }

    private fun setSettledMode(
        newMode: ConversationScrollMode,
        forceRevision: Boolean = false,
    ) {
        val settled = newMode.requireSettled()
        if (forceRevision || settled != settledMode) intentRevision++
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

        /**
         * Keeps distance-independent navigation responsive: snap near a far target, then animate only
         * the final few rows. Message-backed callers can re-resolve after each snap because paging may
         * insert or remove list headers while the command is suspended.
         */
        suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int = 0,
            resolveIndex: () -> Int = { index },
        ) {
            ensureCurrent()
            var targetIndex = resolveIndex().coerceAtLeast(0)
            var repositionAttempts = 0
            while (
                repositionAttempts < MAX_TARGET_REPOSITION_ATTEMPTS &&
                prePositionIfFar(targetIndex)
            ) {
                repositionAttempts++
                ensureCurrent()
                targetIndex = resolveIndex().coerceAtLeast(0)
            }
            if (isFar(targetIndex)) {
                writer.scrollToItem(targetIndex, scrollOffset)
            } else {
                writer.animateScrollToItem(targetIndex, scrollOffset)
            }
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

        private suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (serial != commandSerial) throw CancellationException("Superseded conversation scroll command")
        }
    }
}

internal suspend fun ConversationScrollCoordinator.jumpToNewest(targetIndex: Int): Boolean =
    programmaticJump(
        targetMessageId = null,
        reason = ConversationScrollReason.JumpToNewest,
        resultingMode = ConversationScrollMode.FollowingTail,
    ) {
        animateScrollToItem(targetIndex)
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

internal suspend fun ConversationScrollCoordinator.restoreViewport(
    snapshot: ConversationScrollBookmark,
    resolveAnchorIndex: (ConversationScrollAnchor) -> Int?,
    resolveTailIndex: () -> Int,
    frameCount: Int = 24,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
): Boolean =
    when (snapshot.settledMode) {
        ConversationScrollMode.FollowingTail ->
            followTailIfAllowed(
                resolveTailIndex = resolveTailIndex,
                reason = ConversationScrollReason.LifecycleResume,
                frameCount = frameCount,
                awaitFrame = awaitFrame,
            )
        is ConversationScrollMode.ReadingHistory ->
            restoreBookmark(snapshot, resolveAnchorIndex = resolveAnchorIndex)
        else -> false
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
}

private fun ConversationScrollMode.requireSettled(): ConversationScrollMode {
    require(this is ConversationScrollMode.FollowingTail || this is ConversationScrollMode.ReadingHistory) {
        "Transient scroll mode cannot be persisted: $this"
    }
    return this
}
