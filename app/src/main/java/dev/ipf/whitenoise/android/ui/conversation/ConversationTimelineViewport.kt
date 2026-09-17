package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Separates the one native list's paint area from the measured foreground composer's reading occlusion. */
@Stable
internal class ConversationTimelineViewport(
    private val listState: LazyListState,
) {
    var enabled by mutableStateOf(false)
    private var measuredPadding by mutableStateOf(0 to 0)
    var foregroundHeightPx by mutableIntStateOf(0)
        private set
    var compactHeightPx by mutableIntStateOf(0)
        private set
    private var chromeHeightPx by mutableIntStateOf(0)
    private var paintBoundsInWindow by mutableStateOf<Rect?>(null)

    /** Records the actual shared surface interval independently of custom panels and keyboard insets. */
    fun onComposerMeasured(
        foregroundHeightPx: Int,
        compactHeightPx: Int,
    ) {
        this.foregroundHeightPx = foregroundHeightPx.coerceAtLeast(0)
        if (compactHeightPx > 0) this.compactHeightPx = compactHeightPx
    }

    /** Includes keyboard/custom panel clearance, permitting overlap only within the measured foreground. */
    fun onBottomChromeMeasured(heightPx: Int) {
        chromeHeightPx = heightPx.coerceAtLeast(0)
    }

    /** Computes paint extension; the remaining Scaffold padding continues to exclude keyboard/custom panels. */
    fun overlayPadding(
        density: Density,
        enabled: Boolean = this.enabled,
    ): Dp =
        with(density) {
            (if (enabled) foregroundHeightPx.coerceAtMost(chromeHeightPx) else 0).toDp()
        }

    /**
     * Only overlap present in this native layout pass is removed, avoiding
     * mixed-frame measurement projections. The transcript is reversed, so the
     * composer's clearance is the list's before-content padding.
     */
    fun readingLayoutInfo(): LazyListLayoutInfo {
        val native = listState.layoutInfo
        val (base, overlap) = measuredPadding
        val laidOutOverlap = (native.beforeContentPadding - base).coerceIn(0, overlap)
        return conversationReadingLayoutInfo(native, laidOutOverlap)
    }

    /** Commits padding only after the corresponding native list measure has completed. */
    fun onPaddingMeasured(
        basePx: Int,
        overlapPx: Int,
    ) {
        measuredPadding = basePx.coerceAtLeast(0) to overlapPx.coerceAtLeast(0)
    }

    /** Returns the clear measured height consumed by native centering, tail, selection and speech owners. */
    fun readingHeightPx(): Int = readingLayoutInfo().viewportSize.height

    /** Stores actual list coordinates; this never modifies or substitutes the native list's physical bounds. */
    fun onPaintViewportMeasured(coordinates: LayoutCoordinates) {
        val position = coordinates.positionInWindow()
        paintBoundsInWindow =
            Rect(
                position.x,
                position.y,
                position.x + coordinates.size.width,
                position.y + coordinates.size.height,
            )
    }

    /** Intersects actual paint bounds with the current measured clear interval. */
    val readingBoundsInWindow: Rect?
        get() =
            paintBoundsInWindow?.let { bounds ->
                bounds.copy(bottom = (bounds.top + readingHeightPx()).coerceAtMost(bounds.bottom))
            }
}

/**
 * Read-only occlusion projection for native geometry consumers. Item offsets retain their physical coordinates;
 * the native LazyListState and its scroll writers remain untouched. Partial rows remain visible and usable.
 *
 * The transcript is reversed, so the composer occludes the list's low-offset
 * edge: the clear interval starts after the overlap rather than ending before it.
 */
internal fun conversationReadingLayoutInfo(
    native: LazyListLayoutInfo,
    overlapPx: Int,
): LazyListLayoutInfo {
    val overlap = overlapPx.coerceIn(0, native.viewportSize.height)
    if (overlap == 0) return native
    val clearStart = native.viewportStartOffset + overlap
    return object : LazyListLayoutInfo by native {
        override val viewportSize = IntSize(native.viewportSize.width, native.viewportSize.height - overlap)
        override val viewportStartOffset = clearStart
        override val beforeContentPadding = (native.beforeContentPadding - overlap).coerceAtLeast(0)
        override val visibleItemsInfo =
            native.visibleItemsInfo.filter {
                it.offset + it.size > clearStart && it.offset < native.viewportEndOffset
            }
    }
}

/** Records the exact padding passed through this native list measure, including unchanged-total transitions. */
internal fun Modifier.measureConversationTimelinePadding(
    viewport: ConversationTimelineViewport,
    basePadding: Dp,
    foregroundOverlap: Dp,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        viewport.onPaddingMeasured(basePadding.roundToPx(), foregroundOverlap.roundToPx())
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

/** Removes only the foreground overlap, leaving the existing top, horizontal and keyboard/panel inset owner. */
internal fun conversationUnderlayScaffoldPadding(
    padding: PaddingValues,
    overlap: Dp,
): PaddingValues =
    object : PaddingValues by padding {
        /** Bottom padding minus the overlap the foreground chrome covers, never negative. */
        override fun calculateBottomPadding() = (padding.calculateBottomPadding() - overlap).coerceAtLeast(0.dp)
    }

/** Paints native rows beneath rounded foreground chrome, but rejects covered pointer targets and hidden semantics. */
@Composable
internal fun Modifier.timelineReadingExposure(viewport: ConversationTimelineViewport): Modifier {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val clear = viewport.readingBoundsInWindow
    val hidden = bounds?.let { row -> clear != null && (row.top >= clear.bottom || row.bottom <= clear.top) } == true
    return this
        .onGloballyPositioned {
            val position = it.positionInWindow()
            bounds = Rect(position.x, position.y, position.x + it.size.width, position.y + it.size.height)
        }.then(if (hidden) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier)
        .pointerInput(viewport) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val rowTop = bounds?.top ?: return@awaitEachGesture
                val visible = viewport.readingBoundsInWindow ?: return@awaitEachGesture
                val outside = down.position.y + rowTop !in visible.top..<visible.bottom
                if (outside) {
                    down.consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        }
}
