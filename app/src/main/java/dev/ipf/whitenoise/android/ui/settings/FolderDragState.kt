package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import kotlin.math.roundToInt

/** A drag previews movement locally; only dropping a surviving folder commits a reorder. */
internal class FolderDragState(
    private val list: LazyListState,
    private val ids: () -> List<String>,
) {
    var folderId: String? by mutableStateOf(null)
        private set
    private var center by mutableFloatStateOf(0f)
    private var initialOffset = 0
    private var height = 0
    private var scrolled by mutableFloatStateOf(0f)
    private var sourceIds = emptyList<String>()
    private var expectedIds = emptyList<String>()
    private val renderedTranslations = mutableMapOf<String, Float>()
    private var releasePositions = emptyMap<String, Float>()
    private var releaseFraction by mutableFloatStateOf(0f)
    var releasing by mutableStateOf(false)
        private set
    var releaseGeneration by mutableIntStateOf(0)
        private set
    val targetId by derivedStateOf { target(ids()) }

    /** Captures authoritative order and the grabbed row's measured height, not a fixed row size. */
    fun start(id: String) {
        if (folderId != null) return
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        releaseGeneration++
        sourceIds = ids().toList()
        center = item.offset + item.size / 2f
        initialOffset = item.offset
        height = item.size
        scrolled = 0f
        folderId = id
    }

    /** Pointer deltas stay in viewport coordinates; consumed scrolling is tracked separately. */
    fun drag(distance: Float) {
        if (releasing) return
        center += distance
    }

    /** Ownership/order invalidation retires every transient coordinate without writing the store. */
    fun cancel() {
        folderId = null
        releasing = false
        releasePositions = emptyMap()
        renderedTranslations.clear()
    }

    /** The dragged row follows the finger; siblings leave a measured insertion-sized gap. */
    fun translation(id: String): Float {
        if (folderId == null) return 0f
        if (releasing) {
            val position = releasePositions[id] ?: return 0f
            val layout = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return 0f
            return (position - layout.offset) * (1f - releaseFraction)
        }
        if (id == folderId) return center - initialOffset + scrolled - height / 2f
        val current = ids()
        val from = current.indexOf(folderId)
        val to = current.indexOf(targetId)
        val index = current.indexOf(id)
        val displacement = (height + list.layoutInfo.mainAxisItemSpacing).toFloat()
        return when {
            from < to && index > from && index <= to -> -displacement
            to >= 0 && to < from && index >= to && index < from -> displacement
            else -> 0f
        }
    }

    /** Records actual placed offsets so a release starts at the visible, possibly mid-animation frame. */
    fun recordTranslation(id: String, translation: Float) {
        renderedTranslations[id] = translation
    }

    /** Only consumed scroll changes the grabbed point's compensation. */
    fun recordScroll(distance: Float) {
        scrolled += distance
    }

    /** Original layout slots remain stable while sibling draw offsets animate around the gap. */
    fun target(ids: List<String>): String? {
        if (folderId == null) return null
        return list.layoutInfo.visibleItemsInfo
            .filter { it.key in ids }
            .minByOrNull { kotlin.math.abs(center - it.offset - it.size / 2f) }
            ?.key as? String
    }

    /** Commits the previewed insertion once, retaining the visible frame until the store responds. */
    fun drop(ids: List<String>, onMove: (String, Int) -> Unit) {
        val id = folderId ?: return
        if (releasing || ids != sourceIds) return cancel()
        val from = ids.indexOf(id)
        val to = ids.indexOf(target(ids))
        if (from < 0 || to < 0) return cancel()
        releasePositions =
            list.layoutInfo.visibleItemsInfo
                .mapNotNull { item ->
                    (item.key as? String)?.let { key -> key to (item.offset + (renderedTranslations[key] ?: 0f)) }
                }.toMap()
        expectedIds = ids.toMutableList().apply { add(to, removeAt(from)) }
        releaseFraction = 0f
        releasing = true
        releaseGeneration++
        if (from != to) onMove(id, to - from)
    }

    /** Accept only our saved reorder; an external mutation cannot inherit stale drag coordinates. */
    fun orderChanged(current: List<String>) {
        if (folderId == null) return
        if (current != sourceIds && (!releasing || current != expectedIds)) cancel()
    }

    /**
     * Reads the authoritative layout after the synchronous store callback. A rejected save leaves
     * the old order in place and animates back there, without claiming the preview was persisted.
     */
    suspend fun settle(enabled: Boolean) {
        if (!releasing) return
        val generation = releaseGeneration
        withFrameNanos { }
        if (!releasing) return
        Animatable(0f).animateTo(1f, if (enabled) tween(180) else snap()) {
            if (generation == releaseGeneration) releaseFraction = value
        }
        if (generation == releaseGeneration) cancel()
    }

    /** Bounds auto-scroll to the current viewport; release never keeps scrolling the list. */
    fun edgeScroll(edge: Float): Float {
        if (releasing) return 0f
        val info = list.layoutInfo
        return when {
            center < info.viewportStartOffset + edge -> -SCROLL_PER_FRAME
            center > info.viewportEndOffset - edge -> SCROLL_PER_FRAME
            else -> 0f
        }
    }

    private companion object {
        const val SCROLL_PER_FRAME = 12f
    }
}

/** Stop stale gestures if folders change; edge scrolling reaches folders outside the first viewport. */
@Composable
internal fun rememberFolderDrag(
    list: LazyListState,
    ids: List<String>,
): FolderDragState {
    val latestIds by rememberUpdatedState(ids)
    val drag = remember(list) { FolderDragState(list) { latestIds } }
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    val enabled = rememberSystemAnimationsEnabled()
    LaunchedEffect(ids) { drag.orderChanged(ids) }
    LaunchedEffect(drag.releaseGeneration, enabled) { drag.settle(enabled) }
    LaunchedEffect(drag.folderId) {
        while (drag.folderId != null) {
            withFrameNanos { }
            val distance = drag.edgeScroll(edge)
            if (distance != 0f) drag.recordScroll(list.scrollBy(distance))
        }
    }
    return drag
}

/** Keep callbacks fresh without restarting the pointer coroutine during an active drag. */
@Composable
internal fun Modifier.folderDragHandle(
    id: String,
    drag: FolderDragState,
    ids: List<String>,
    onMove: (String, Int) -> Unit,
): Modifier {
    val latestIds by rememberUpdatedState(ids)
    val latestMove by rememberUpdatedState(onMove)
    val pinnable = LocalPinnableContainer.current
    return pointerInput(id, drag) {
        var pinned: PinnableContainer.PinnedHandle? = null
        try {
            detectDragGestures(
                onDragStart = {
                    pinned = pinnable?.pin()
                    drag.start(id)
                },
                onDragEnd = {
                    if (drag.folderId == id) drag.drop(latestIds, latestMove)
                    pinned?.release()
                    pinned = null
                },
                onDragCancel = {
                    if (drag.folderId == id) drag.cancel()
                    pinned?.release()
                    pinned = null
                },
            ) { change, amount ->
                change.consume()
                if (drag.folderId == id) drag.drag(amount.y)
            }
        } finally {
            pinned?.release()
            if (drag.folderId == id) drag.cancel()
        }
    }
}

/** Siblings animate around an insertion gap; the grabbed row and release frame remain exact. */
@Composable
internal fun folderDragRowModifier(
    id: String,
    drag: FolderDragState,
): Modifier {
    val enabled = rememberSystemAnimationsEnabled()
    val moving = drag.folderId == id
    val siblingOffset by
        animateFloatAsState(
            targetValue = if (moving || drag.releasing) 0f else drag.translation(id),
            animationSpec = if (enabled) tween(120) else snap(),
            label = "folder insertion gap",
        )
    return Modifier.zIndex(if (moving) 1f else 0f).offset {
        val translation = if (moving || drag.releasing) drag.translation(id) else siblingOffset
        drag.recordTranslation(id, translation)
        IntOffset(0, translation.roundToInt())
    }
}
