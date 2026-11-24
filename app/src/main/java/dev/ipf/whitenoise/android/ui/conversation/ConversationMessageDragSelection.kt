package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.ui.common.DragSelectionVisibleItem
import dev.ipf.whitenoise.android.ui.common.anchoredDragSelection
import dev.ipf.whitenoise.android.ui.common.dragSelectionAutoScrollDelta
import dev.ipf.whitenoise.android.ui.common.reverseLazyListDragSelectionEndpoint

/**
 * Batch-selects the messages between the row a held vertical drag started on and the row under the pointer, and
 * scrolls the reversed transcript while the pointer rests at its top or bottom edge.
 *
 * The drag anchor, pointer and transcript bounds last as long as one instance, which the screen remembers on the
 * keys those states always used. [listState], [timelineViewport] and [currentScrollAnchor] are fixed for an instance:
 * both objects are remember keys, and the anchor function reads only the controller, the list state and the
 * viewport. Every value the screen recomputes per composition comes from [latest], read once per call. [latest] is
 * remembered on the same keys, so an instance replaced by a state reset keeps the values of the last composition it
 * served and never reads its replacement's selection.
 */
internal class ConversationMessageDragSelection(
    private val listState: LazyListState,
    private val timelineViewport: ConversationTimelineViewport,
    private val currentScrollAnchor: () -> ConversationScrollAnchor,
    private val latest: State<Inputs>,
) {
    /**
     * The values one composition of the screen computed for drag selection. A plain class, compared by identity
     * like the lambda it replaced, so updating it never compares the timeline lists.
     */
    internal class Inputs(
        val orderedTimelineIds: List<String>,
        val timelineIdSet: Set<String>,
        val selectableTimelineIds: Set<String>,
        val timelineSelectionById: Map<String, BatchMessageSelection>,
        val selectedMessages: SnapshotStateMap<String, BatchMessageSelection>,
        val scrollCoordinator: ConversationScrollCoordinator,
    )

    /** The presentation id of the row the active drag started on, or null when no drag is active. */
    var dragAnchorTimelineId by mutableStateOf<String?>(null)
        private set
    private var dragPointerWindowY by mutableStateOf<Float?>(null)
    private var transcriptWindowTop by mutableFloatStateOf(0f)
    private var transcriptHeightPx by mutableFloatStateOf(0f)

    /** Records where the reading transcript sits in the window, for the endpoint and edge-scroll math. */
    fun updateTranscriptBounds(bounds: Rect) {
        transcriptWindowTop = bounds.top
        transcriptHeightPx = bounds.height
    }

    /** Anchors a new drag on [timelineId] with the pointer at [pointerWindowY]. */
    fun startMessageDrag(
        timelineId: String,
        pointerWindowY: Float,
    ) {
        dragAnchorTimelineId = timelineId
        dragPointerWindowY = pointerWindowY
    }

    /** Records the held pointer for the edge auto-scroll loop. */
    fun moveMessageDragPointer(pointerWindowY: Float) {
        dragPointerWindowY = pointerWindowY
    }

    /** Resolves the live range without rewriting unchanged selection keys; true while a nonempty range is active. */
    @Suppress("ReturnCount") // Guard clauses keep invalid live-timeline gesture state explicit.
    fun updateMessageDragSelection(pointerWindowY: Float): Boolean {
        with(latest.value) {
            val anchorId = dragAnchorTimelineId ?: return false
            val endpointId =
                reverseLazyListDragSelectionEndpoint(
                    timelineViewport.readingLayoutInfo().visibleItemsInfo.mapNotNull { visible ->
                        val id = visible.key as? String
                        id
                            ?.takeIf(timelineIdSet::contains)
                            ?.let {
                                DragSelectionVisibleItem(
                                    key = it,
                                    start = visible.offset.toFloat(),
                                    end = (visible.offset + visible.size).toFloat(),
                                )
                            }
                    },
                    pointerWindowY = pointerWindowY,
                    viewportWindowTop = transcriptWindowTop,
                    // A reversed list's item offsets start above its bottom content padding.
                    viewportHeight =
                        listState.layoutInfo.viewportSize.height - listState.layoutInfo.beforeContentPadding,
                ) ?: return false
            if (endpointId == anchorId && selectedMessages.isEmpty()) return false
            val nextTimelineIds =
                anchoredDragSelection(
                    orderedIds = orderedTimelineIds,
                    eligibleIds = selectableTimelineIds,
                    anchorId = anchorId,
                    endpointId = endpointId,
                )
            val nextSelections = nextTimelineIds.mapNotNull(timelineSelectionById::get)
            val nextMessageIds = nextSelections.mapTo(linkedSetOf()) { it.action.messageId }
            if (nextMessageIds == selectedMessages.keys) return nextMessageIds.isNotEmpty()
            selectedMessages.clear()
            nextSelections.forEach { selection -> selectedMessages[selection.action.messageId] = selection }
            return nextMessageIds.isNotEmpty()
        }
    }

    /** Ends a message drag, optionally clearing the selection, and settles reading at the current anchor. */
    fun finishMessageDrag(clearSelection: Boolean) {
        with(latest.value) {
            val hadActiveDrag = dragAnchorTimelineId != null
            dragAnchorTimelineId = null
            dragPointerWindowY = null
            if (clearSelection) selectedMessages.clear()
            if (hadActiveDrag) scrollCoordinator.settleReadingAt(currentScrollAnchor())
        }
    }

    /**
     * Ends a drag whose anchor left the timeline and scrolls while its pointer is held at an edge.
     * The shared delta uses physical top/bottom signs for a normal list. This transcript is reversed:
     * positive logical scroll reveals older rows at the physical top, so only this caller negates it.
     * Resolve the stationary pointer again after the native list has consumed the scroll.
     *
     * Non-restartable like the screen-local function it replaced, so its reads stay in the screen's scope.
     */
    @Composable
    @NonRestartableComposable
    fun messageDragSelectionEffects(
        orderedTimelineIds: List<String>,
        selectableTimelineIds: Set<String>,
    ) {
        val dragSelectionDensity = LocalDensity.current
        val dragEdgeThresholdPx = with(dragSelectionDensity) { 56.dp.toPx() }
        val dragMaxScrollStepPx = with(dragSelectionDensity) { 18.dp.toPx() }
        LaunchedEffect(orderedTimelineIds, selectableTimelineIds) {
            val anchorId = dragAnchorTimelineId ?: return@LaunchedEffect
            if (anchorId !in orderedTimelineIds || anchorId !in selectableTimelineIds) {
                finishMessageDrag(clearSelection = true)
            }
        }

        LaunchedEffect(dragAnchorTimelineId, listState) {
            while (dragAnchorTimelineId != null) {
                withFrameNanos { }
                val pointerWindowY = dragPointerWindowY ?: continue
                val pointerY = pointerWindowY - transcriptWindowTop
                val scrollDelta =
                    dragSelectionAutoScrollDelta(
                        pointerY = pointerY,
                        viewportStart = 0f,
                        viewportEnd = transcriptHeightPx,
                        edgeThreshold = dragEdgeThresholdPx,
                        maxStep = dragMaxScrollStepPx,
                    )
                if (scrollDelta != 0f) {
                    listState.scrollBy(-scrollDelta)
                    updateMessageDragSelection(pointerWindowY)
                }
            }
        }
    }
}

/**
 * Remembers [ConversationMessageDragSelection] on the keys its drag state always used (the controller, the
 * conversation's account and the runtime generation) plus the list state and viewport, which change only with the
 * controller. [inputs] is updated on every composition like `rememberUpdatedState`, but in a state remembered on the
 * same keys: an instance sees the latest values while it is current and keeps its last ones once a reset replaces it,
 * as the screen-local functions it replaces kept the values they captured.
 */
@Composable
@Suppress("LongParameterList") // Explicit keys and dependencies, so a stale capture cannot hide in a provider.
internal fun rememberConversationMessageDragSelection(
    controller: ConversationController,
    accountRef: String?,
    runtimeGeneration: Int,
    listState: LazyListState,
    timelineViewport: ConversationTimelineViewport,
    currentScrollAnchor: () -> ConversationScrollAnchor,
    inputs: ConversationMessageDragSelection.Inputs,
): ConversationMessageDragSelection {
    val latest =
        remember(controller, accountRef, runtimeGeneration, listState, timelineViewport) { mutableStateOf(inputs) }
    latest.value = inputs
    return remember(controller, accountRef, runtimeGeneration, listState, timelineViewport) {
        ConversationMessageDragSelection(
            listState = listState,
            timelineViewport = timelineViewport,
            currentScrollAnchor = currentScrollAnchor,
            latest = latest,
        )
    }
}
