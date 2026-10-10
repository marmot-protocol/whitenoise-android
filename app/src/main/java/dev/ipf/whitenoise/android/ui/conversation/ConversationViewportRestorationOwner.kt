package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

/** One screen generation's presentation-only initial and post-initial restoration decisions. */
internal class ConversationViewportRestorationOwner(
    private val coordinator: ConversationScrollCoordinator,
    private val reanchorGate: ConversationPostInitialReanchorGate,
) {
    var isActive: Boolean = true
        private set

    // The landing that still owns the viewport's reading start. State, so the row-height effect
    // observes it appearing, and always replaced by the next initial position this owner completes.
    private var readingStart by mutableStateOf<ConversationReadingStartIntent?>(null)
    private var committedReadingStart: Pair<ConversationReadingStartProbe, ConversationReadingStartPlacement>? = null

    fun dispose() {
        isActive = false
    }

    /**
     * Places [position] under the hidden-transcript contract. A reading-start position additionally
     * needs [readingStartProbe], which supplies the live geometry its measured settle reads.
     */
    suspend fun commitInitialPosition(
        position: ConversationViewportInitialPosition,
        captureLayout: () -> ConversationInitialAnchorLayout,
        awaitFrame: suspend () -> Unit = { withFrameNanos { } },
        readingStartProbe: ConversationReadingStartProbe? = null,
    ): Boolean {
        if (!isActive) return false
        val awaitCurrentFrame: suspend () -> Unit = {
            awaitFrame()
            currentCoroutineContext().ensureActive()
            if (!isActive) throw CancellationException("Viewport owner disposed")
        }
        val committed =
            when {
                position.mode is ConversationScrollMode.FollowingTail ->
                    coordinator.commitInitialTailAnchor(position.index, captureLayout, awaitCurrentFrame)
                position.readingStart ->
                    commitReadingStart(
                        position,
                        requireNotNull(readingStartProbe).copy(awaitLayout = awaitCurrentFrame),
                    )
                else ->
                    coordinator.commitInitialAnchor(
                        targetMessageId = position.targetMessageId,
                        reason = position.reason,
                        resultingMode = position.mode,
                        targetIndex = position.index,
                        pixelOffset = position.anchor.pixelOffset,
                        captureLayout = captureLayout,
                        awaitFrame = awaitCurrentFrame,
                    )
            }
        currentCoroutineContext().ensureActive()
        return committed && isActive
    }

    /** Remembers the placed offset, not the planned one, because the settle may have corrected it. */
    private suspend fun commitReadingStart(
        position: ConversationViewportInitialPosition,
        probe: ConversationReadingStartProbe,
    ): Boolean {
        val placement =
            coordinator.commitInitialReadingStartAnchor(
                targetMessageId = requireNotNull(position.targetMessageId),
                resultingMode = position.mode,
                probe = probe,
                reason = position.reason,
            )
        committedReadingStart = placement?.let { probe to it }
        return placement != null
    }

    /** Settles the committed position as the durable reading intent, with the placed offset for a landing. */
    fun completeInitialPosition(
        position: ConversationViewportInitialPosition,
        structure: ConversationTimelineStructure,
        viewportHeight: Int,
    ): Boolean {
        if (!isActive) return false
        readingStart = null
        if (position.mode is ConversationScrollMode.ReadingHistory) {
            val landing = committedReadingStart.takeIf { position.readingStart }
            val anchor =
                landing?.let { (_, placed) ->
                    position.anchor.copy(listIndex = placed.index, pixelOffset = placed.offsetPx)
                } ?: position.anchor
            coordinator.settleReadingAt(anchor)
            readingStart =
                landing?.let { (probe, placed) ->
                    ConversationReadingStartIntent(anchor, probe, placed, coordinator.intentToken.revision)
                }
        }
        // The seeded-tail path shares this gate; it remains the single baseline.
        reanchorGate.commit(structure, viewportHeight)
        return true
    }

    /** Fresh geometry of the landing's target while it still owns the viewport, null once nothing does. */
    fun readingStartGeometry(): Pair<Int, Int>? = readingStart?.takeIf { it.isCurrent(coordinator) }?.geometry()

    /** Re-settles a landing after its row or viewport changed size, unless a gesture already took over. */
    suspend fun onReadingStartGeometry() {
        if (isActive) resettleReadingStart()
    }

    /**
     * Reruns a still-current landing's measured settle. False hands the correction to the ordinary
     * history reanchor, which is right only for a position that is not a reading start.
     */
    private suspend fun resettleReadingStart(): Boolean {
        val intent = readingStart ?: return false
        return when {
            !intent.isCurrent(coordinator) -> {
                readingStart = null
                false
            }
            // Another command owns the list right now, so it must not be cancelled for a correction.
            coordinator.mode !is ConversationScrollMode.ReadingHistory -> true
            else -> {
                intent.resettle(coordinator)
                true
            }
        }
    }

    /** Corrects a changed clear viewport height, rerunning a landing's measured settle rather than a stale offset. */
    suspend fun onViewportHeight(
        height: Int,
        presentation: ConversationViewportPresentation,
        navigation: ConversationViewportNavigation,
    ) {
        if (!isActive) return
        val changed = reanchorGate.onViewportHeight(height)
        val canCorrect = presentation.anchored && !presentation.imeIsOpen && !coordinator.foregroundRestoreInProgress
        if (!changed || !canCorrect) return
        when (coordinator.mode) {
            ConversationScrollMode.FollowingTail ->
                coordinator.programmaticJump(
                    targetMessageId = null,
                    reason = ConversationScrollReason.ViewportChange,
                    resultingMode = ConversationScrollMode.FollowingTail,
                ) { scrollToTail(navigation.tailIndex()) }
            is ConversationScrollMode.ReadingHistory ->
                if (!adoptReaderPositionWhenLandingLeft(navigation.currentAnchor) && !resettleReadingStart()) {
                    coordinator.reanchorReadingHistory(navigation.resolveAnchor)
                }
            else -> Unit
        }
    }

    /**
     * Reanchors after a row or header structure change, preferring a landing's measured settle over a stale offset.
     * A reader who already scrolled the landed message away keeps the position they are at, which [currentAnchor]
     * supplies.
     */
    suspend fun onStructure(
        structure: ConversationTimelineStructure,
        anchored: Boolean,
        currentAnchor: () -> ConversationScrollAnchor? = { null },
        resolveAnchor: (ConversationScrollAnchor) -> Int?,
    ) {
        if (!isActive) return
        val changed = reanchorGate.onStructure(structure)
        val needsReanchor = anchored && changed
        if (needsReanchor && !adoptReaderPositionWhenLandingLeft(currentAnchor) && !resettleReadingStart()) {
            coordinator.reanchorReadingHistory(resolveAnchor)
        }
    }

    /**
     * Hands the viewport to a reader who scrolled the landed message out of view. Wheel, keyboard and accessibility
     * scrolling raise no drag, so nothing else told the coordinator, and its anchor still names the landed message.
     * A landing whose row is no longer laid out is retired and the reader's own anchor becomes the reading position,
     * so neither the measured settle nor the ordinary reanchor drags the list back. True when the landing was released.
     */
    private fun adoptReaderPositionWhenLandingLeft(currentAnchor: () -> ConversationScrollAnchor?): Boolean {
        val intent = readingStart
        val left = intent != null && intent.isCurrent(coordinator) && intent.geometry() == null
        if (left) {
            readingStart = null
            currentAnchor()?.let(coordinator::settleReadingAt)
        }
        return left
    }
}

internal data class ConversationViewportPresentation(
    val anchored: Boolean,
    val imeIsOpen: Boolean,
)

internal data class ConversationViewportNavigation(
    val resolveAnchor: (ConversationScrollAnchor) -> Int?,
    /** Where the reader actually is now, or null when nothing anchorable is on screen. */
    val currentAnchor: () -> ConversationScrollAnchor? = { null },
    val tailIndex: () -> Int,
)

internal data class ConversationViewportInitialPosition(
    val anchor: ConversationScrollAnchor,
    val mode: ConversationScrollMode,
    val reason: ConversationScrollReason = ConversationScrollReason.InitialAnchor,
    val targetMessageId: String? = anchor.messageId,
    // A notification landing: the row's beginning, not its newest edge, meets the top of the clear viewport.
    val readingStart: Boolean = false,
) {
    val index: Int get() = anchor.listIndex
}

/** Uses only the rendered bounded window; it never invents or caches native history. */
internal fun conversationViewportEntryPosition(
    rows: List<Pair<String, String>>,
    unreadId: String?,
    trailingRowCount: Int,
): ConversationViewportInitialPosition? {
    val tail = conversationTimelineTailListIndex(rows.size, trailingRowCount) ?: return null
    val unreadIndex = unreadId?.let { id -> rows.indexOfFirst { it.second == id } } ?: -1
    val index =
        if (unreadIndex >= 0) {
            conversationTimelineListIndex(unreadIndex, rows.size, trailingRowCount)
        } else {
            tail
        }
    return ConversationViewportInitialPosition(
        anchor = ConversationScrollAnchor(index, 0, rows.getOrNull(unreadIndex)?.first, unreadId),
        mode =
            if (unreadIndex >= 0) {
                ConversationScrollMode.ReadingHistory(unreadId, 0)
            } else {
                ConversationScrollMode.FollowingTail
            },
    )
}

internal fun conversationViewportSavedPosition(
    restore: ConversationScrollSnapshot,
    rows: List<Pair<String, String>>,
    trailingRowCount: Int,
): ConversationViewportInitialPosition? {
    val tail = conversationTimelineTailListIndex(rows.size, trailingRowCount) ?: return null
    val index =
        conversationScrollRestoreListIndex(
            restore,
            rows.map { it.first },
            rows.map { it.second },
            trailingRowCount,
        ).coerceAtLeast(tail)
    val row = rows.getOrNull(conversationTimelineIndexForListIndex(index, rows.size, trailingRowCount))
    return ConversationViewportInitialPosition(
        anchor =
            ConversationScrollAnchor(
                index,
                restore.firstVisibleItemScrollOffset,
                row?.first ?: restore.anchorItemId,
                row?.second ?: restore.anchorMessageIdHex,
            ),
        mode = ConversationScrollMode.ReadingHistory(restore.anchorMessageIdHex, restore.firstVisibleItemScrollOffset),
        reason = ConversationScrollReason.SavedRestore,
        targetMessageId = restore.anchorMessageIdHex,
    )
}

@Composable
internal fun rememberConversationViewportRestorationOwner(
    controllerIdentity: Any,
    coordinator: ConversationScrollCoordinator,
    gate: ConversationPostInitialReanchorGate,
): ConversationViewportRestorationOwner {
    val owner =
        remember(controllerIdentity, coordinator, gate) {
            ConversationViewportRestorationOwner(coordinator, gate)
        }
    DisposableEffect(owner) {
        onDispose { owner.dispose() }
    }
    return owner
}
