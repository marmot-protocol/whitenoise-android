package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
 * AMOLED fill for modal bottom sheets. The fill must ride the sheet's own
 * Surface via `containerColor`: a positional modifier (the previous
 * clip + background + border approach for #801) draws outside the sheet's
 * drag/settle translation on current material3, so the black panel painted at
 * the window top while the sheet's translated content was clipped away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun amoledSheetContainerColor(): Color = if (isAmoledSurfaceTheme()) Color.Black else BottomSheetDefaults.ContainerColor
