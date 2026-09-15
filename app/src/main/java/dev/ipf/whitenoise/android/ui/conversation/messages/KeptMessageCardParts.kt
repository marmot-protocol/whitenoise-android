@file:Suppress("FunctionNaming") // Compose UI entry points use PascalCase.

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem

/** Geometry the kept-message card and its parts share. */
internal object KeptMessageMetrics {
    /** Side inset the card keeps from both screen edges. */
    val SideMargin = 24.dp

    /** Clearance above the card so it never covers the conversation header. */
    val TopInset = 64.dp

    /** Floor for the composer clearance below the card. */
    val MinimumBottomInset = 80.dp

    /** Extra breathing room between the card and the composer. */
    val BottomGap = 8.dp

    /** Ceiling on the expanded card so a long message still shows the transcript. */
    val MaximumExpandedHeight = 480.dp

    /** Below this the collapsed preview is dropped and only the header remains. */
    val MinimumPreviewHeight = 144.dp

    /** Minimum height of the draggable header row. */
    val HeaderMinimumHeight = 48.dp

    /** Inner padding of the card's header and body. */
    val ContentInset = 16.dp

    /** Gap between the pagination gutter and the message preview. */
    val PaginationGap = 8.dp

    /** Resting elevation of the floating card. */
    val Elevation = 6.dp

    /** Lines of plain text the collapsed preview shows. */
    const val COLLAPSED_PREVIEW_LINES = 4

    /** Fraction past which a released drag snaps to the trailing edge. */
    const val EDGE_SNAP_FRACTION = 0.5f
}

/**
 * Where the card rests, as fractions of the space available to it, so a
 * rotation or a keyboard does not throw it off screen.
 */
@Stable
internal class KeptMessagePlacement(
    initialX: Float = 1f,
    initialY: Float = 0.35f,
) {
    var horizontalFraction by mutableFloatStateOf(initialX)
    var verticalFraction by mutableFloatStateOf(initialY)

    /** Snaps the card to whichever side edge the release left it nearer. */
    fun snapToNearestEdge() {
        horizontalFraction = if (horizontalFraction < KeptMessageMetrics.EDGE_SNAP_FRACTION) 0f else 1f
    }

    /** Moves the card by one drag delta, clamped to the space it may occupy. */
    fun dragBy(
        delta: Offset,
        maxX: Float,
        availableHeight: Float,
        maxY: Float,
    ) {
        if (maxX > 0f) horizontalFraction = (horizontalFraction + delta.x / maxX).coerceIn(0f, 1f)
        if (availableHeight > 0f) {
            val ceiling = maxY / availableHeight
            verticalFraction = (verticalFraction + delta.y / availableHeight).coerceIn(0f, ceiling)
        }
    }
}

/** Remembers the card's resting position for as long as the account stays put. */
@Composable
internal fun rememberKeptMessagePlacement(accountRef: String): KeptMessagePlacement {
    val placement = remember(accountRef) { KeptMessagePlacement() }
    return placement
}

/** Drag handle behaviour for the header: free movement, then a snap to the nearest side. */
internal fun Modifier.keptMessageDragHandle(
    placement: KeptMessagePlacement,
    maxX: Float,
    availableHeight: Float,
    maxY: Float,
): Modifier =
    pointerInput(maxX, availableHeight, maxY) {
        detectDragGestures(onDragEnd = placement::snapToNearestEdge) { change, amount ->
            change.consume()
            placement.dragBy(amount, maxX, availableHeight, maxY)
        }
    }

/**
 * The overflow menu on the card's header: jump to the message, stop keeping it,
 * clear the stack, and the three placement shortcuts the pointer cannot reach.
 */
@Composable
internal fun KeptMessageMenu(
    onOpenMessage: () -> Unit,
    onRemove: () -> Unit,
    onClear: () -> Unit,
    onMoveSide: () -> Unit,
    onMovePlacement: (top: Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(KEPT_MESSAGE_MENU_TAG)) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.floating_options),
            )
        }
        WhiteNoiseDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            items =
                listOf(
                    keptMessageMenuItem(R.string.go_to_message, R.drawable.ic_reply) {
                        open = false
                        onOpenMessage()
                    },
                    keptMessageMenuItem(R.string.floating_remove, R.drawable.ic_close) {
                        open = false
                        onRemove()
                    },
                    keptMessageMenuItem(R.string.floating_clear, R.drawable.ic_delete) {
                        open = false
                        onClear()
                    },
                    keptMessageMenuItem(R.string.floating_move_side, null) {
                        open = false
                        onMoveSide()
                    },
                    keptMessageMenuItem(R.string.floating_move_top, null) {
                        open = false
                        onMovePlacement(true)
                    },
                    keptMessageMenuItem(R.string.floating_move_bottom, null) {
                        open = false
                        onMovePlacement(false)
                    },
                ),
        )
    }
}

/** One overflow-menu row, resolved from its label and optional leading drawable. */
@Composable
private fun keptMessageMenuItem(
    @StringRes label: Int,
    @DrawableRes icon: Int?,
    onClick: () -> Unit,
): WhiteNoiseMenuItem = WhiteNoiseMenuItem(label = stringResource(label), onClick = onClick, icon = icon)

/**
 * The vertical segment indicator beside a multi-message card. A moving window of
 * at most five segments keeps the marks legible however many messages are kept.
 */
@Composable
internal fun KeptMessagePagination(
    index: Int,
    count: Int,
) {
    val selected = MaterialTheme.colorScheme.onSurface
    val idle = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier =
            Modifier
                .width(PAGINATION_WIDTH)
                .height(PAGINATION_HEIGHT)
                .testTag(KEPT_MESSAGE_PAGINATION_TAG),
    ) {
        val visible = count.coerceAtMost(PAGINATION_MAX_SEGMENTS)
        val first = (index - PAGINATION_WINDOW_LEAD).coerceIn(0, (count - visible).coerceAtLeast(0))
        val gap = PAGINATION_GAP.toPx()
        val segment = (size.height - gap * (visible - 1)) / visible
        repeat(visible) { slot ->
            val top = slot * (segment + gap)
            drawLine(
                color = if (first + slot == index) selected else idle,
                start = Offset(size.width / 2, top + size.width / 2),
                end = Offset(size.width / 2, top + segment - size.width / 2),
                strokeWidth = size.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The kept-message pager. Boundary copies make the swipe continuous; once a
 * swipe settles on one of them the pager recentres invisibly on the real page.
 */
@Composable
internal fun KeptMessagePager(
    entries: List<KeptMessageEntry>,
    selectedIndex: Int,
    modifier: Modifier,
    onSelect: (KeptMessageKey) -> Unit,
    page: @Composable (KeptMessageEntry) -> Unit,
) = key(entries.map { it.key }) {
    val pages = remember(entries.size) { KeptMessagePages(entries.size) }
    val pager = rememberPagerState(initialPage = pages.pageFor(selectedIndex), pageCount = { pages.pageCount })
    LaunchedEffect(selectedIndex) {
        if (!pager.isScrollInProgress && pages.messageAt(pager.currentPage) != selectedIndex) {
            pager.scrollToPage(pages.pageFor(selectedIndex))
        }
    }
    LaunchedEffect(pager, entries) {
        snapshotFlow { pager.isScrollInProgress to pager.settledPage }.collect { (scrolling, pageIndex) ->
            if (!scrolling) {
                val index = pages.messageAt(pageIndex)
                entries.getOrNull(index)?.let { onSelect(it.key) }
                if (pages.isBoundary(pageIndex)) pager.scrollToPage(pages.pageFor(index))
            }
        }
    }
    HorizontalPager(
        state = pager,
        modifier = modifier,
        userScrollEnabled = entries.size > 1,
        key = { it },
        verticalAlignment = Alignment.Top,
    ) { pageIndex ->
        entries.getOrNull(pages.messageAt(pageIndex))?.let { page(it) }
    }
}

internal const val KEPT_MESSAGE_MENU_TAG = "kept-message.menu"
internal const val KEPT_MESSAGE_PAGINATION_TAG = "kept-message.pagination"
private val PAGINATION_WIDTH = 2.dp
private val PAGINATION_HEIGHT = 32.dp
private val PAGINATION_GAP = 2.dp
private const val PAGINATION_MAX_SEGMENTS = 5
private const val PAGINATION_WINDOW_LEAD = 2
