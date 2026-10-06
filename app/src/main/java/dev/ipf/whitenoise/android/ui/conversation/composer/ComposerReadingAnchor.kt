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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Let a short finger tap enter the unfocused editor and a stationary hold open Paste, while
 * keeping both gestures away from BasicTextField's focus and selection handlers. Once focused,
 * the modifier is removed and the platform owns caret placement, selection and paste again.
 * Mouse and stylus input pass through unchanged. The outer reading-scroll owner still sees
 * early vertical drags first.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun PointerInputScope.composerUnfocusedTouchFocusGestures(
    onTap: (Offset) -> Unit,
    onLongPress: () -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
    awaitPointerEventScope {
        var trackedPointer: PointerId? = null
        var downAtMillis = 0L
        var lastEventAtMillis = 0L
        var downPosition = Offset.Zero
        var cancelled = false
        var longPressDispatched = false
        while (true) {
            val remaining = longPressTimeoutMillis - (lastEventAtMillis - downAtMillis)
            val awaitingLongPress = trackedPointer != null && !cancelled && !longPressDispatched
            val event =
                if (awaitingLongPress && remaining > 0) {
                    withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Initial) }
                } else {
                    awaitPointerEvent(PointerEventPass.Initial)
                }
            if (event == null) {
                // A still, held finger produces no Move event. The timeout must open Paste
                // without waiting for the finger to lift.
                if (trackedPointer != null && !cancelled) {
                    longPressDispatched = true
                    onLongPress()
                }
                continue
            }
            when (event.type) {
                PointerEventType.Press -> {
                    val change = event.changes.firstOrNull { it.pressed && it.type == PointerType.Touch }
                    if (change != null) {
                        if (trackedPointer == null) {
                            trackedPointer = change.id
                            downAtMillis = change.uptimeMillis
                            lastEventAtMillis = downAtMillis
                            downPosition = change.position
                            cancelled = false
                            longPressDispatched = false
                        } else {
                            cancelled = true
                        }
                        change.consume()
                    }
                }
                PointerEventType.Move, PointerEventType.Release -> {
                    val change = event.changes.firstOrNull { it.id == trackedPointer }
                    if (change != null) {
                        lastEventAtMillis = change.uptimeMillis
                        val movedBeyondSlop = (change.position - downPosition).getDistance() > touchSlop
                        cancelled = cancelled || change.isConsumed || movedBeyondSlop
                        val elapsed = change.uptimeMillis - downAtMillis
                        val released = !change.pressed && !cancelled
                        change.consume()
                        if (!change.pressed) {
                            trackedPointer = null
                            if (released && !longPressDispatched) {
                                if (elapsed >= longPressTimeoutMillis) onLongPress() else onTap(change.position)
                            }
                        } else if (!cancelled && !longPressDispatched && elapsed >= longPressTimeoutMillis) {
                            longPressDispatched = true
                            onLongPress()
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

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
 * long-press timeout without clearing slop belongs to selection when the field is
 * focused and is left alone for the rest of that gesture. Wheel and trackpad ticks scroll
 * directly. Every owned movement first reports [onReadingScroll] so
 * caret-following can suspend for the current draft. This remains the one
 * reading-scroll owner; the unfocused touch gate only decides when to focus.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun PointerInputScope.composerEditorReadingScrollGestures(
    // Returns whether the dispatch actually moved the viewport, so no-op
    // events are never consumed away from ancestor scroll containers.
    scrollBy: (Float) -> Boolean,
    onReadingScroll: () -> Unit,
    onScrollInterrupted: () -> Unit = {},
    onFling: (Float) -> Unit = {},
    acceptsTouchDown: (Offset) -> Boolean = { true },
) {
    val touchSlop = viewConfiguration.touchSlop
    val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
    val velocityTracker = VelocityTracker()
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
                            onScrollInterrupted()
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
                        onScrollInterrupted()
                        val change = event.changes.first()
                        // Only finger drags are ambiguous between reading and
                        // selection. Mouse and stylus drags are drag-select by
                        // platform convention (wheel/trackpad scrolling arrives
                        // as Scroll events above), so they pass through to the
                        // text field untouched.
                        if (change.type == PointerType.Touch && acceptsTouchDown(change.position)) {
                            velocityTracker.resetTracking()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
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
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val frameDeltaY = change.position.y - change.previousPosition.y
                        if (!owningDrag) {
                            accumulatedX += change.position.x - change.previousPosition.x
                            accumulatedY += frameDeltaY
                            when {
                                change.uptimeMillis - trackedDownAtMillis > longPressTimeoutMillis ->
                                    yieldedToSelection = true
                                abs(accumulatedY) > touchSlop && abs(accumulatedY) > abs(accumulatedX) -> {
                                    if (scrollBy(-accumulatedY)) {
                                        owningDrag = true
                                        onReadingScroll()
                                    }
                                }
                            }
                        } else {
                            onReadingScroll()
                            scrollBy(-frameDeltaY)
                        }
                        if (owningDrag) change.consume()
                    }
                }
                PointerEventType.Release -> {
                    val released = event.changes.firstOrNull { it.id == trackedPointer && !it.pressed }
                    if (released != null) {
                        if (owningDrag && !released.isConsumed) {
                            velocityTracker.addPosition(released.uptimeMillis, released.position)
                            onFling(-velocityTracker.calculateVelocity().y)
                        } else if (owningDrag) {
                            onScrollInterrupted()
                        }
                        trackedPointer = null
                    }
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
    outerGutterPx: Float = 0f,
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
        composerOverflowThumbXPx(
            editorWidthPx = size.width,
            thumbWidthPx = thumbWidth,
            edgeInsetPx = inset,
            outerGutterPx = outerGutterPx,
            rightToLeft = layoutDirection == LayoutDirection.Rtl,
        )
    drawRoundRect(
        color = color,
        topLeft = Offset(x, progress * travel),
        size = Size(thumbWidth, thumbHeight),
        cornerRadius = CornerRadius(thumbWidth / 2f),
    )
}

/**
 * Where the editor's overflow thumb is painted. The editor fills its row, so with an [outerGutterPx]
 * the thumb goes in the inset beside it and a draft's last glyphs, caret and selection handles keep the
 * whole row width; without one it falls back to the trailing edge inside the editor.
 */
internal fun composerOverflowThumbXPx(
    editorWidthPx: Float,
    thumbWidthPx: Float,
    edgeInsetPx: Float,
    outerGutterPx: Float,
    rightToLeft: Boolean,
): Float {
    val centred = ((outerGutterPx - thumbWidthPx) / 2f).coerceAtLeast(0f)
    return when {
        outerGutterPx <= 0f && rightToLeft -> edgeInsetPx
        outerGutterPx <= 0f -> editorWidthPx - thumbWidthPx - edgeInsetPx
        rightToLeft -> centred - outerGutterPx
        else -> editorWidthPx + centred
    }
}

private val COMPOSER_SCROLLBAR_THUMB_WIDTH = 3.dp
private val COMPOSER_SCROLLBAR_MIN_THUMB = 24.dp
private val COMPOSER_SCROLLBAR_EDGE_INSET = 2.dp
