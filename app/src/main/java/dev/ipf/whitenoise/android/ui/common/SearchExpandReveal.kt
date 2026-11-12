package dev.ipf.whitenoise.android.ui.common

import android.graphics.Picture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import kotlin.math.ceil
import kotlin.math.min

internal const val SEARCH_EXPAND_REVEAL_MILLIS = APP_MOTION_OPEN_MILLIS
internal const val SEARCH_COLLAPSE_MILLIS = APP_MOTION_CLOSE_MILLIS

/** The bubble leaves the icon quickly and settles at full width. */
private val SearchExpandEasing: Easing = AppMotionOpenEasing

/** The closing bubble starts gently and lands in the icon. */
private val SearchCollapseEasing: Easing = AppMotionCloseEasing

/** Share of the collapse, at its end, over which the icon-sized bubble fades out. */
private const val SEARCH_COLLAPSE_FADE = 0.3f

/**
 * Closing half of the search reveal. The open field records an inert picture of its last frame;
 * when search closes, the field leaves composition at once (no focus, IME, Back handler or editable
 * state survives) and [searchCollapseHost] draws that picture shrinking back toward the end edge.
 */
@Stable
internal class SearchCollapse {
    internal var picture: Picture? = null
    internal var fieldInRoot: Offset = Offset.Zero
    internal var fieldSize: Size = Size.Zero
    internal var hostInRoot: Offset = Offset.Zero
    internal var hostHeightPx: Int = 0
    internal var revealProgress: Float = 1f
    internal var contentKey: Any? = null
    internal val progress = Animatable(1f)

    /** True while the closed field's picture is shrinking; callers may hold its space until then. */
    var closing by mutableStateOf(false)
        internal set

    internal fun clear() {
        closing = false
        picture = null
        revealProgress = 1f
    }
}

/** Provided by the closest [searchCollapseHost] so the field can record itself for the close. */
internal val LocalSearchCollapse = staticCompositionLocalOf<SearchCollapse?> { null }

/**
 * Remembers the close animation for a search field shown while [open]. With animations off, or if
 * the field never drew, closing is instant.
 */
@Composable
internal fun rememberSearchCollapse(
    open: Boolean,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
): SearchCollapse {
    val collapse = remember { SearchCollapse() }
    LaunchedEffect(collapse, open, animationsEnabled) {
        if (open) {
            collapse.closing = false
            return@LaunchedEffect
        }
        if (!animationsEnabled || collapse.picture == null || collapse.fieldSize.isEmpty()) {
            collapse.clear()
            return@LaunchedEffect
        }
        collapse.closing = true
        try {
            collapse.progress.snapTo(collapse.revealProgress)
            collapse.progress.animateTo(0f, tween(SEARCH_COLLAPSE_MILLIS, easing = SearchCollapseEasing))
        } finally {
            collapse.clear()
        }
    }
    return collapse
}

/**
 * Draws the closing search field over this element. Put it on an ancestor that stays composed when
 * the field closes (the top bar or the slot the field sat in), and provide [collapse] through
 * [LocalSearchCollapse] to the field.
 */
internal fun Modifier.searchCollapseHost(collapse: SearchCollapse): Modifier =
    onGloballyPositioned {
        collapse.hostInRoot = it.positionInRoot()
        collapse.hostHeightPx = it.size.height
    }.drawWithContent {
        drawContent()
        if (!collapse.closing) return@drawWithContent
        val picture = collapse.picture ?: return@drawWithContent
        val p = collapse.progress.value.coerceIn(0f, 1f)
        val field = collapse.fieldSize
        val origin = collapse.fieldInRoot - collapse.hostInRoot
        val bubble = searchExpandBubble(field.width, field.height, p, layoutDirection)
        val clip = Path().apply { addRoundRect(RoundRect(bubble, CornerRadius(field.height / 2f))) }
        val alpha = (p / SEARCH_COLLAPSE_FADE).coerceIn(0f, 1f)
        translate(origin.x, origin.y) {
            clipPath(clip) {
                drawIntoCanvas { canvas ->
                    val layered = alpha < 1f
                    if (layered) canvas.saveLayer(field.toRect(), Paint().apply { this.alpha = alpha })
                    canvas.nativeCanvas.drawPicture(picture)
                    if (layered) canvas.restore()
                }
            }
        }
    }

/**
 * Grows a search field that a search icon just opened out of that icon: the field is drawn as a
 * pill the height of the field, starting icon-sized at the end edge and widening toward the start
 * edge (left in LTR, right in RTL). The field is visible from the first frame, so the icon turns
 * straight into the end of the pill and the rest is uncovered as it widens.
 *
 * This is draw-only. Layout, focus, the IME and semantics see the full-width field from the first
 * frame, so typing during the reveal behaves the same as typing after it. With animations off,
 * the field is shown at full width immediately.
 *
 * Under a [searchCollapseHost], the field also keeps an inert picture of its latest frame so the
 * host can shrink it back into the icon after the field closes. [contentKey] (the query) makes the
 * picture refresh when the text changes.
 */
@Composable
internal fun Modifier.searchExpandReveal(
    contentKey: Any? = null,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
    fixedProgress: Float? = null,
): Modifier {
    val progress = remember { Animatable(if (animationsEnabled) 0f else 1f) }
    val collapse = LocalSearchCollapse.current
    LaunchedEffect(animationsEnabled) {
        if (animationsEnabled) {
            progress.animateTo(1f, tween(SEARCH_EXPAND_REVEAL_MILLIS, easing = SearchExpandEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    val positioned =
        if (collapse == null) {
            this
        } else {
            onGloballyPositioned {
                collapse.fieldInRoot = it.positionInRoot()
                collapse.fieldSize = it.size.toSize()
            }
        }
    return positioned.drawWithContent {
        val p = (fixedProgress ?: progress.value).coerceIn(0f, 1f)
        if (collapse != null) {
            collapse.contentKey = contentKey
            collapse.revealProgress = p
            recordSearchField(collapse)
        }
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        val bubble = searchExpandBubble(size.width, size.height, p, layoutDirection)
        val path = Path().apply { addRoundRect(RoundRect(bubble, CornerRadius(size.height / 2f))) }
        clipPath(path) { this@drawWithContent.drawContent() }
    }
}

private fun ContentDrawScope.recordSearchField(collapse: SearchCollapse) {
    val width = ceil(size.width).toInt()
    val height = ceil(size.height).toInt()
    if (width <= 0 || height <= 0) return
    val picture = collapse.picture ?: Picture().also { collapse.picture = it }
    val recording = Canvas(picture.beginRecording(width, height))
    draw(this, layoutDirection, recording, size) { this@recordSearchField.drawContent() }
    picture.endRecording()
}

/** The pill drawn at [progress]: icon-sized at the end edge at 0, the whole field at 1. */
internal fun searchExpandBubble(
    width: Float,
    height: Float,
    progress: Float,
    layoutDirection: LayoutDirection,
): Rect {
    val start = min(height, width)
    val bubbleWidth = start + (width - start) * progress
    val left = if (layoutDirection == LayoutDirection.Rtl) 0f else width - bubbleWidth
    return Rect(
        Offset(left, 0f),
        Size(bubbleWidth, height),
    )
}
