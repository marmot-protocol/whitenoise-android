package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.DragSelectionVisibleItem
import dev.ipf.whitenoise.android.ui.common.anchoredDragSelection
import dev.ipf.whitenoise.android.ui.common.dragSelectionAutoScrollDelta
import dev.ipf.whitenoise.android.ui.common.dragSelectionEndpoint

/**
 * Selects the chats between the row a held vertical drag started on and the row under the pointer, and scrolls the
 * list while the pointer rests at its top or bottom edge.
 *
 * The drag anchor, pointer and list bounds last as long as one instance, which the screen remembers on the keys
 * those states always used. [selectedChatIds] lives as long as the screen, and [completeSelection] is remembered on
 * the same keys as the instance. Values the screen recomputes per composition arrive as [Inputs] from the caller: a
 * row passes the inputs of the composition that built its callback, and the edge-scroll loop the inputs of the
 * composition that launched it, as the screen-local functions this replaces captured them.
 */
internal class ChatListDragSelection(
    private val selectedChatIds: SnapshotStateSet<String>,
    completeSelectionState: MutableState<ChatListCompleteSelection?>,
) {
    private var completeSelection by completeSelectionState

    /**
     * The values one composition of the screen computed for drag selection. A plain class, compared by identity
     * like the local function it replaced, so passing it never compares the visible rows.
     */
    internal class Inputs(
        val chatListState: LazyListState,
        val visibleChatIds: Set<String>,
        val orderedVisibleChatIds: List<String>,
    )

    /** The row id the active drag started on, or null when no drag is active. */
    var dragAnchorChatId by mutableStateOf<String?>(null)
        private set
    private var dragPointerWindowY by mutableStateOf<Float?>(null)
    private var chatListWindowTop by mutableFloatStateOf(0f)
    private var chatListHeightPx by mutableFloatStateOf(0f)

    /** Records where the chat list sits in the window, for the endpoint and edge-scroll math. */
    fun updateListBounds(coordinates: LayoutCoordinates) {
        chatListWindowTop = coordinates.positionInWindow().y
        chatListHeightPx = coordinates.size.height.toFloat()
    }

    /** Anchors a new drag on [chatId] with the pointer at [pointerWindowY]. */
    fun startChatDrag(
        chatId: String,
        pointerWindowY: Float,
    ) {
        dragAnchorChatId = chatId
        dragPointerWindowY = pointerWindowY
    }

    /** Records the held pointer for the edge auto-scroll loop. */
    fun moveChatDragPointer(pointerWindowY: Float) {
        dragPointerWindowY = pointerWindowY
    }

    /** Selects the range from the anchor to the row under the pointer; true when it resolved a range. */
    @Suppress("ReturnCount") // Guard clauses keep invalid live-list gesture state explicit.
    fun updateChatDragSelection(
        pointerWindowY: Float,
        inputs: Inputs,
    ): Boolean {
        with(inputs) {
            val anchorId = dragAnchorChatId ?: return false
            val pointerY = pointerWindowY - chatListWindowTop
            val endpointId =
                dragSelectionEndpoint(
                    chatListState.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                        val id = visible.key as? String
                        id
                            ?.takeIf(visibleChatIds::contains)
                            ?.let {
                                DragSelectionVisibleItem(
                                    key = it,
                                    start = visible.offset.toFloat(),
                                    end = (visible.offset + visible.size).toFloat(),
                                )
                            }
                    },
                    pointerY,
                ) ?: return false
            if (endpointId == anchorId && selectedChatIds.isEmpty()) return false
            completeSelection = null
            val next =
                anchoredDragSelection(
                    orderedIds = orderedVisibleChatIds,
                    eligibleIds = visibleChatIds,
                    anchorId = anchorId,
                    endpointId = endpointId,
                )
            selectedChatIds.clear()
            selectedChatIds.addAll(next)
            return true
        }
    }

    /** Ends a chat drag, optionally clearing the selection. */
    fun finishChatDrag(clearSelection: Boolean) {
        dragAnchorChatId = null
        dragPointerWindowY = null
        if (clearSelection) {
            completeSelection = null
            selectedChatIds.clear()
        }
    }

    /**
     * Ends a drag when the list's dataset changes or its anchor leaves the visible rows, and scrolls while the
     * pointer is held at an edge, starting a new viewport generation when an auto-scroll session starts.
     *
     * Non-restartable, so its reads stay in the screen's scope. Each effect keeps the [inputs] and thresholds of the
     * composition that launched it.
     */
    @Composable
    @NonRestartableComposable
    fun chatDragSelectionEffects(
        datasetKey: ChatListDatasetKey,
        inputs: Inputs,
        programmaticViewportGenerationState: MutableLongState,
    ) {
        val density = LocalDensity.current
        val dragEdgeThresholdPx = with(density) { 56.dp.toPx() }
        val dragMaxScrollStepPx = with(density) { 18.dp.toPx() }
        var programmaticViewportGeneration by programmaticViewportGenerationState
        LaunchedEffect(datasetKey) {
            if (dragAnchorChatId != null) finishChatDrag(clearSelection = true)
        }
        LaunchedEffect(inputs.visibleChatIds) {
            val anchorId = dragAnchorChatId ?: return@LaunchedEffect
            if (anchorId !in inputs.visibleChatIds) finishChatDrag(clearSelection = true)
        }

        LaunchedEffect(dragAnchorChatId, inputs.chatListState) {
            var autoScrollActive = false
            while (dragAnchorChatId != null) {
                withFrameNanos { }
                val pointerWindowY = dragPointerWindowY ?: continue
                val pointerY = pointerWindowY - chatListWindowTop
                val scrollDelta =
                    dragSelectionAutoScrollDelta(
                        pointerY = pointerY,
                        viewportStart = 0f,
                        viewportEnd = chatListHeightPx,
                        edgeThreshold = dragEdgeThresholdPx,
                        maxStep = dragMaxScrollStepPx,
                    )
                if (scrollDelta != 0f) {
                    if (chatListAutoScrollSessionStarts(autoScrollActive, scrollDelta)) {
                        programmaticViewportGeneration += 1L
                    }
                    autoScrollActive = true
                    inputs.chatListState.scrollBy(scrollDelta)
                    updateChatDragSelection(pointerWindowY, inputs)
                } else {
                    autoScrollActive = false
                }
            }
        }
    }
}

/**
 * Remembers [ChatListDragSelection] on the keys its drag state always used, the account and the runtime generation,
 * plus [completeSelection], which the screen remembers on the same two keys. The drag state therefore resets exactly
 * when it did as screen locals. [selectedChatIds] lives as long as the screen and is not a key.
 */
@Composable
internal fun rememberChatListDragSelection(
    accountRef: String?,
    runtimeGeneration: Int,
    selectedChatIds: SnapshotStateSet<String>,
    completeSelection: MutableState<ChatListCompleteSelection?>,
): ChatListDragSelection =
    remember(accountRef, runtimeGeneration, completeSelection) {
        ChatListDragSelection(selectedChatIds = selectedChatIds, completeSelectionState = completeSelection)
    }
