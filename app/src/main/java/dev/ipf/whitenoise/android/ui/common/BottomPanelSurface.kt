package dev.ipf.whitenoise.android.ui.common

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
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
import android.view.WindowInsets as PlatformWindowInsets

/**
 * How far up the side wall a display corner of reported radius R can curve. Many phones mask a smooth
 * (squircle) corner whose curve starts about 1.53 R above the bottom edge, not at R.
 */
private const val DISPLAY_CORNER_CURVE_EXTENT_FACTOR = 1.53f

/**
 * Bottom corner radius for a full-width panel whose bottom edge sits [bottomGap] above the window's
 * bottom edge. The display corner of reported radius [displayCornerRadius] curves away from the side
 * walls up to [DISPLAY_CORNER_CURVE_EXTENT_FACTOR] times that radius above the bottom edge. A panel corner
 * of radius `extent - bottomGap` lies inside that curve, so the panel keeps [designRadius] unless the
 * display needs more. Using the bare reported radius would leave the two curves touching where the
 * panel's side wall starts to bend, and a smooth display corner clips the wall there.
 */
internal fun bottomPanelBottomCornerRadius(
    displayCornerRadius: Dp,
    bottomGap: Dp,
    designRadius: Dp = Radii.xxl,
): Dp = maxOf(designRadius, displayCornerRadius * DISPLAY_CORNER_CURVE_EXTENT_FACTOR - bottomGap)

/**
 * Corner radii in pixels for a bottom panel of [height]: the bottom corners keep [bottomRadius] (capped by
 * the height and half the width), and the top corners give way first when the panel is too short for both.
 * [androidx.compose.foundation.shape.RoundedCornerShape] would shrink all four corners instead, pulling
 * the bottom corners back into the display's curve.
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
 * Shape for panels docked above the system navigation bar or keyboard: the sheet's 28 dp top corners,
 * and bottom corners that stay inside a rounded display corner (see [bottomPanelBottomCornerRadius]).
 */
@Composable
internal fun rememberBottomPanelShape(): Shape {
    val view = LocalView.current
    val density = LocalDensity.current
    val navigationBars = WindowInsets.navigationBars
    val ime = WindowInsets.ime
    val bottomInsets = remember(navigationBars, ime) { navigationBars.union(ime) }
    val bottomRadius by remember(view, density, bottomInsets) {
        derivedStateOf {
            val bottomGapPx = bottomInsets.getBottom(density)
            with(density) {
                bottomPanelBottomCornerRadius(
                    displayCornerRadius = displayBottomCornerRadiusPx(view).toDp(),
                    bottomGap = bottomGapPx.toDp(),
                )
            }
        }
    }
    return remember(bottomRadius) { BottomPanelShape(top = Radii.xxl, bottom = bottomRadius) }
}

/**
 * The elevated surface shared by the composer's emoji pane, the disabled-composer notices and the
 * message selection bar. Callers keep their own window inset padding; the surface supplies the
 * rounded, display-corner-safe outline and the AMOLED border.
 */
@Composable
@Suppress("FunctionNaming")
internal fun BottomPanelSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = rememberBottomPanelShape(),
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
