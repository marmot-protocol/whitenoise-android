package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The draft and selection a deliberate reader scroll was armed against.
 * Caret-following stays suspended only while the live field still matches, so
 * any edit, selection move, paste, or bulk replacement re-enables the caret
 * guarantees on the very frame it lands.
 */
internal data class ComposerReadingAnchor(
    val text: String,
    val selection: TextRange,
) {
    fun matches(value: TextFieldValue): Boolean = value.text == text && value.selection == selection

    companion object {
        fun of(value: TextFieldValue): ComposerReadingAnchor = ComposerReadingAnchor(value.text, value.selection)
    }
}

/**
 * The editor viewport's explicit reading-scroll owner. A vertical drag that
 * clears touch slop before the long-press timeout is a scroll: its moves are
 * consumed on the initial pass (so the text field's cursor and selection
 * handlers never see them) and fed to [scrollBy]. A press that holds past the
 * long-press timeout without clearing slop belongs to selection and is left
 * alone for the rest of that gesture. Wheel and trackpad ticks scroll
 * directly. Every owned movement first reports [onReadingScroll] so
 * caret-following can suspend for the current draft. This is one gesture
 * state machine on purpose: splitting it would scatter the slop and
 * long-press yield rules across helpers.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun PointerInputScope.composerEditorReadingScrollGestures(
    // Returns whether the dispatch actually moved the viewport, so no-op
    // events are never consumed away from ancestor scroll containers.
    scrollBy: (Float) -> Boolean,
    onReadingScroll: () -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
    awaitPointerEventScope {
        var trackedPointer: PointerId? = null
        var trackedDownAtMillis = 0L
        var accumulatedX = 0f
        var accumulatedY = 0f
        var owningDrag = false
        var yieldedToSelection = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            when (event.type) {
                PointerEventType.Scroll ->
                    event.changes.forEach { change ->
                        val tick = change.scrollDelta.y
                        if (tick != 0f) {
                            // Density-scaled so a wheel tick travels the same
                            // visual distance as in every other scrollable.
                            // Consume and arm only when the editor actually
                            // moved: a non-overflowing draft or a boundary tick
                            // belongs to the ancestor scroll container.
                            if (scrollBy(tick * COMPOSER_WHEEL_SCROLL_STEP.toPx())) {
                                onReadingScroll()
                                change.consume()
                            }
                        }
                    }
                PointerEventType.Press -> {
                    if (trackedPointer == null) {
                        val change = event.changes.first()
                        // Only finger drags are ambiguous between reading and
                        // selection. Mouse and stylus drags are drag-select by
                        // platform convention (wheel/trackpad scrolling arrives
                        // as Scroll events above), so they pass through to the
                        // text field untouched.
                        if (change.type == PointerType.Touch) {
                            trackedPointer = change.id
                            trackedDownAtMillis = change.uptimeMillis
                            accumulatedX = 0f
                            accumulatedY = 0f
                            owningDrag = false
                            yieldedToSelection = false
                        }
                    }
                }
                PointerEventType.Move -> {
                    val change = event.changes.firstOrNull { it.id == trackedPointer && it.pressed }
                    if (change != null && !yieldedToSelection) {
                        val frameDeltaY = change.position.y - change.previousPosition.y
                        if (!owningDrag) {
                            accumulatedX += change.position.x - change.previousPosition.x
                            accumulatedY += frameDeltaY
                            when {
                                change.uptimeMillis - trackedDownAtMillis > longPressTimeoutMillis ->
                                    yieldedToSelection = true
                                abs(accumulatedY) > touchSlop && abs(accumulatedY) > abs(accumulatedX) -> {
                                    owningDrag = true
                                    onReadingScroll()
                                    scrollBy(-accumulatedY)
                                }
                            }
                        } else {
                            onReadingScroll()
                            scrollBy(-frameDeltaY)
                        }
                        if (owningDrag) change.consume()
                    }
                }
                PointerEventType.Release ->
                    if (event.changes.any { it.id == trackedPointer && !it.pressed }) {
                        trackedPointer = null
                    }
                else -> Unit
            }
        }
    }
}

private val COMPOSER_WHEEL_SCROLL_STEP = 64.dp

/**
 * Paints the clipped editor's scroll affordance: a thin position-tracking
 * thumb on the trailing edge, present only while the draft overflows the
 * viewport. Uses the resize handle's opaque contrast-safe color recipe so it
 * reads in light, dark, and AMOLED themes.
 */
internal fun DrawScope.drawComposerEditorOverflowAffordance(
    scrollValue: Int,
    maxScroll: Int,
    color: Color,
) {
    if (maxScroll <= 0) return
    val viewport = size.height
    val content = viewport + maxScroll
    val thumbHeight = (viewport / content * viewport).coerceAtLeast(COMPOSER_SCROLLBAR_MIN_THUMB.toPx())
    val travel = (viewport - thumbHeight).coerceAtLeast(0f)
    val progress = scrollValue.toFloat() / maxScroll.toFloat()
    val thumbWidth = COMPOSER_SCROLLBAR_THUMB_WIDTH.toPx()
    val inset = COMPOSER_SCROLLBAR_EDGE_INSET.toPx()
    val x =
        if (layoutDirection == LayoutDirection.Rtl) {
            inset
        } else {
            size.width - thumbWidth - inset
        }
    drawRoundRect(
        color = color,
        topLeft = Offset(x, progress * travel),
        size = Size(thumbWidth, thumbHeight),
        cornerRadius = CornerRadius(thumbWidth / 2f),
    )
}

private val COMPOSER_SCROLLBAR_THUMB_WIDTH = 3.dp
private val COMPOSER_SCROLLBAR_MIN_THUMB = 24.dp
private val COMPOSER_SCROLLBAR_EDGE_INSET = 2.dp
