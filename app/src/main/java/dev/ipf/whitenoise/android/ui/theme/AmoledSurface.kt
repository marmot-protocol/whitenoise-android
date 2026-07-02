package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalAmoledSurfaceTheme = staticCompositionLocalOf { false }

@Composable
internal fun isAmoledSurfaceTheme(): Boolean = LocalAmoledSurfaceTheme.current

@Composable
internal fun amoledSurfaceBorderStroke(width: Dp = 1.dp): BorderStroke? =
    if (isAmoledSurfaceTheme()) {
        BorderStroke(width, MaterialTheme.colorScheme.outlineVariant)
    } else {
        null
    }

@Composable
internal fun Modifier.amoledSurfaceBorder(
    shape: Shape,
    width: Dp = 1.dp,
): Modifier =
    amoledSurfaceBorderStroke(width)?.let { stroke ->
        border(stroke, shape)
    } ?: this

/**
 * Sheet surface for AMOLED (issue #801). A `ModalBottomSheet` renders in its
 * own window over the host screen, and its default modifier only strokes the
 * outer node while the sheet's own fill is clipped to the rounded top corners.
 * Applied as a bare `Modifier.border` (the old `amoledModalSheetModifier`) the
 * stroke lived outside that clip, so at the rounded top corners the sheet had
 * no opaque fill and the surface beneath — e.g. a Settings section card with
 * its own grey stroke and rounded corner — bled through the sheet's corners.
 *
 * Clipping to the sheet shape first, then filling opaque black, then drawing
 * the stroke inside that same clipped layer keeps the stroke z-sorted and
 * clipped with the surface: the black fill occludes whatever is beneath and the
 * corners round cleanly instead of exposing the underlying surface.
 */
@Composable
internal fun Modifier.amoledModalSheetSurface(
    shape: Shape,
    width: Dp = 1.dp,
): Modifier =
    amoledSurfaceBorderStroke(width)?.let { stroke ->
        clip(shape)
            .background(Color.Black)
            .border(stroke, shape)
    } ?: this
