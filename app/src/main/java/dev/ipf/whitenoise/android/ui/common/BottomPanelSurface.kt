package dev.ipf.whitenoise.android.ui.common

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.Radii
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlin.math.sqrt
import android.view.WindowInsets as PlatformWindowInsets

/**
 * How far up the side wall a display corner of reported radius R can curve. Many phones mask a smooth
 * (squircle) corner whose curve starts about 1.53 R above the bottom edge, not at R.
 */
private const val DISPLAY_CORNER_CURVE_EXTENT_FACTOR = 1.53f

/** Bottom corner radius of a docked panel, matching the composer pill. */
internal val BottomPanelBottomRadius = Radii.xl

/**
 * Horizontal inset that keeps a panel's [bottomRadius] corners inside a rounded display corner of reported
 * radius [displayCornerRadius] when the panel's bottom edge sits [bottomGap] above the window's bottom edge.
 * The display curve is treated as a circle of [DISPLAY_CORNER_CURVE_EXTENT_FACTOR] times the reported
 * radius, and the panel corner circle must fit inside it. Panels high above the display corner, such as
 * those over the keyboard or a three-button navigation bar, stay full width.
 */
internal fun bottomPanelHorizontalInset(
    displayCornerRadius: Dp,
    bottomGap: Dp,
    bottomRadius: Dp = BottomPanelBottomRadius,
): Dp {
    val extent = (displayCornerRadius * DISPLAY_CORNER_CURVE_EXTENT_FACTOR).value
    val radius = bottomRadius.value
    val rise = extent - bottomGap.value - radius
    if (extent <= radius || rise <= 0f) return 0.dp
    val reach = extent - radius
    return maxOf(0f, reach - sqrt(reach * reach - rise * rise)).dp
}

/**
 * Corner radii in pixels for a bottom panel of [height]: the bottom corners keep [bottomRadius] (capped by
 * the height and half the width), and the top corners give way first when the panel is too short for both.
 * [androidx.compose.foundation.shape.RoundedCornerShape] would shrink all four corners instead, flattening
 * the bottom corners that match the composer.
 */
internal fun bottomPanelCornerRadiiPx(
    width: Float,
    height: Float,
    topRadius: Float,
    bottomRadius: Float,
): Pair<Float, Float> {
    val bottom = bottomRadius.coerceIn(0f, minOf(height, width / 2))
    val top = topRadius.coerceIn(0f, maxOf(0f, minOf(height - bottom, width / 2)))
    return top to bottom
}

/** Rounded rectangle with [top] and [bottom] corner radii; see [bottomPanelCornerRadiiPx]. */
private data class BottomPanelShape(
    private val top: Dp,
    private val bottom: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val (topPx, bottomPx) =
            with(density) {
                bottomPanelCornerRadiiPx(size.width, size.height, top.toPx(), bottom.toPx())
            }
        return Outline.Rounded(
            RoundRect(
                rect = size.toRect(),
                topLeft = CornerRadius(topPx),
                topRight = CornerRadius(topPx),
                bottomRight = CornerRadius(bottomPx),
                bottomLeft = CornerRadius(bottomPx),
            ),
        )
    }
}

/**
 * Side inset for panels docked above the system navigation bar or keyboard, so their composer-sized
 * bottom corners stay inside a rounded display corner (see [bottomPanelHorizontalInset]).
 */
@Composable
private fun rememberBottomPanelHorizontalInset(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val navigationBars = WindowInsets.navigationBars
    val ime = WindowInsets.ime
    val bottomInsets = remember(navigationBars, ime) { navigationBars.union(ime) }
    val inset by remember(view, density, bottomInsets) {
        derivedStateOf {
            val bottomGapPx = bottomInsets.getBottom(density)
            with(density) {
                bottomPanelHorizontalInset(
                    displayCornerRadius = displayBottomCornerRadiusPx(view).toDp(),
                    bottomGap = bottomGapPx.toDp(),
                )
            }
        }
    }
    return inset
}

private val BottomPanelOutline = BottomPanelShape(top = Radii.xxl, bottom = BottomPanelBottomRadius)

/**
 * The elevated surface shared by the composer's emoji pane, the disabled-composer notices and the
 * message selection bar. Callers keep their own window inset padding; the surface supplies the
 * rounded outline, the side inset that keeps it clear of the display corner, and the AMOLED border.
 */
@Composable
@Suppress("FunctionNaming")
internal fun BottomPanelSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.padding(horizontal = rememberBottomPanelHorizontalInset()).fillMaxWidth(),
        shape = BottomPanelOutline,
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
        content = content,
    )
}

/** Larger of the window's bottom display corner radii in pixels, or 0 before API 31 or when unknown. */
private fun displayBottomCornerRadiusPx(view: View): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.rootWindowInsets?.let(::bottomCornerRadiusPx) ?: 0
    } else {
        0
    }

@RequiresApi(Build.VERSION_CODES.S)
private fun bottomCornerRadiusPx(insets: PlatformWindowInsets): Int =
    maxOf(
        insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
        insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0,
    )
