package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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

    fun dispose() {
        isActive = false
    }

    suspend fun commitInitialPosition(
        position: ConversationViewportInitialPosition,
        captureLayout: () -> ConversationInitialAnchorLayout,
        awaitFrame: suspend () -> Unit = { withFrameNanos { } },
    ): Boolean {
        if (!isActive) return false
        val awaitCurrentFrame: suspend () -> Unit = {
            awaitFrame()
            currentCoroutineContext().ensureActive()
            if (!isActive) throw CancellationException("Viewport owner disposed")
        }
        val committed =
            if (position.mode is ConversationScrollMode.FollowingTail) {
                coordinator.commitInitialTailAnchor(position.index, captureLayout, awaitCurrentFrame)
            } else {
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

    fun completeInitialPosition(
        position: ConversationViewportInitialPosition,
        structure: ConversationTimelineStructure,
        viewportHeight: Int,
    ): Boolean {
        if (!isActive) return false
        if (position.mode is ConversationScrollMode.ReadingHistory) {
            coordinator.settleReadingAt(position.anchor)
        }
        // The seeded-tail path shares this gate; it remains the single baseline.
        reanchorGate.commit(structure, viewportHeight)
        return true
    }

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
                coordinator.reanchorReadingHistory(navigation.resolveAnchor)
            else -> Unit
        }
    }

    suspend fun onStructure(
        structure: ConversationTimelineStructure,
        anchored: Boolean,
        resolveAnchor: (ConversationScrollAnchor) -> Int?,
    ) {
        if (!isActive) return
        val changed = reanchorGate.onStructure(structure)
        if (anchored && changed) coordinator.reanchorReadingHistory(resolveAnchor)
    }
}

internal data class ConversationViewportPresentation(
    val anchored: Boolean,
    val imeIsOpen: Boolean,
)

internal data class ConversationViewportNavigation(
    val resolveAnchor: (ConversationScrollAnchor) -> Int?,
    val tailIndex: () -> Int,
)

internal data class ConversationViewportInitialPosition(
    val anchor: ConversationScrollAnchor,
    val mode: ConversationScrollMode,
    val reason: ConversationScrollReason = ConversationScrollReason.InitialAnchor,
    val targetMessageId: String? = anchor.messageId,
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
