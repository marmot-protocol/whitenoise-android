package dev.ipf.whitenoise.android.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

/** Decorative position within one measured reading surface, never a history percentage. */
internal data class ReadingScrollPosition(
    val progress: Float,
    val visibleFraction: Float,
)

internal fun readerScrollPosition(
    value: Int,
    maximum: Int,
    viewport: Int,
): ReadingScrollPosition? {
    val validRange = maximum > 0 && maximum != Int.MAX_VALUE && value in 0..maximum
    if (viewport <= 0 || !validRange) return null
    return ReadingScrollPosition(
        progress = value.toFloat() / maximum,
        visibleFraction = viewport.toFloat() / (viewport.toFloat() + maximum),
    )
}

/** Reads movement in an effect and alpha in drawing; neither read invalidates the bubble tree. */
@Composable
internal fun rememberReadingIndicatorAlpha(
    owner: Any,
    enabled: Boolean,
    isScrolling: () -> Boolean,
): Animatable<Float, AnimationVector1D> {
    val alpha = remember(owner) { Animatable(0f) }
    val scrolling = rememberUpdatedState(isScrolling)
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    LaunchedEffect(alpha, enabled, fade) {
        snapshotFlow { enabled && scrolling.value() }.collectLatest { active ->
            if (active) alpha.snapTo(1f) else alpha.animateTo(0f, fade)
        }
    }
    return alpha
}

/** Stays in the reader's existing text gutter and adds no pointer or accessibility target. */
@Composable
internal fun Modifier.readerScrollIndicator(
    state: ScrollState,
    owner: Any,
    enabled: Boolean = true,
): Modifier {
    val alpha = rememberReadingIndicatorAlpha(owner, enabled) { state.isScrollInProgress }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return clipToBounds().drawWithContent {
        drawContent()
        if (enabled) {
            readerScrollPosition(state.value, state.maxValue, state.viewportSize)?.let {
                drawReadingScrollIndicator(it, color, alpha.value)
            }
        }
    }
}

/** A thin rounded thumb; position follows measurement directly, only visibility animates. */
internal fun DrawScope.drawReadingScrollIndicator(
    position: ReadingScrollPosition,
    color: Color,
    alpha: Float,
    top: Float = 0f,
    height: Float = size.height,
) {
    val inset = 4.dp.toPx()
    val track = height - inset * 2
    val validPosition = position.progress in 0f..1f && position.visibleFraction > 0f && position.visibleFraction < 1f
    val withinViewport = top >= 0f && top + height <= size.height + 1f
    if (!validPosition || !withinViewport || track <= 0f) return
    if (!(alpha > 0f)) return
    val width = 3.dp.toPx()
    val thumb = (track * position.visibleFraction).coerceIn(24.dp.toPx().coerceAtMost(track), track)
    val x = if (layoutDirection == LayoutDirection.Rtl) 6.dp.toPx() else size.width - 6.dp.toPx() - width
    drawRoundRect(
        color = color.copy(alpha = color.alpha * alpha.coerceIn(0f, 1f) * 0.65f),
        topLeft = Offset(x, top + inset + position.progress * (track - thumb)),
        size = Size(width, thumb),
        cornerRadius = CornerRadius(width / 2),
    )
}
