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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.Radii
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import android.view.WindowInsets as PlatformWindowInsets

/**
 * Bottom corner radius for a full-width panel whose bottom edge sits [bottomGap] above the window's
 * bottom edge. A rounded display corner of radius [displayCornerRadius] cuts into any corner whose
 * radius is below `displayCornerRadius - bottomGap`; a larger radius always stays inside the visible
 * display, so the panel keeps [designRadius] unless the display needs more.
 */
internal fun bottomPanelBottomCornerRadius(
    displayCornerRadius: Dp,
    bottomGap: Dp,
    designRadius: Dp = Radii.xxl,
): Dp = maxOf(designRadius, displayCornerRadius - bottomGap)

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
    return remember(bottomRadius) {
        RoundedCornerShape(
            topStart = Radii.xxl,
            topEnd = Radii.xxl,
            bottomStart = bottomRadius,
            bottomEnd = bottomRadius,
        )
    }
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
