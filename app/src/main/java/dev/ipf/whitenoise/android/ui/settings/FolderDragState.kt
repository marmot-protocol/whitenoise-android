package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
    val targetId by derivedStateOf { target(ids()) }

    fun start(id: String) {
        if (folderId != null) return
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        center = item.offset + item.size / 2f
        initialOffset = item.offset
        height = item.size
        scrolled = 0f
        folderId = id
    }

    fun drag(distance: Float) {
        center += distance
    }

    fun cancel() {
        folderId = null
    }

    fun translation(id: String): Float {
        if (id != folderId) return 0f
        return center - initialOffset + scrolled - height / 2f
    }

    fun recordScroll(distance: Float) {
        scrolled += distance
    }

    fun target(ids: List<String>): String? {
        if (folderId == null) return null
        return list.layoutInfo.visibleItemsInfo
            .filter { it.key in ids }
            .minByOrNull { kotlin.math.abs(center - it.offset - it.size / 2f) }
            ?.key as? String
    }

    fun drop(
        ids: List<String>,
        onMove: (String, Int) -> Unit,
    ) {
        val id = folderId
        val from = ids.indexOf(id)
        val to = ids.indexOf(target(ids))
        cancel()
        val validMove = from >= 0 && to >= 0 && from != to
        if (id != null && validMove) onMove(id, to - from)
    }

    fun edgeScroll(edge: Float): Float {
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
    LaunchedEffect(ids) { drag.cancel() }
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

/** Lift the moving row above its peers and outline its current drop target. */
@Composable
internal fun folderDragRowModifier(
    id: String,
    drag: FolderDragState,
    target: String?,
): Modifier {
    val moving = drag.folderId == id
    val highlight = target == id && !moving
    return Modifier
        .zIndex(if (moving) 1f else 0f)
        .offset { IntOffset(0, drag.translation(id).roundToInt()) }
        .then(if (highlight) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
}
