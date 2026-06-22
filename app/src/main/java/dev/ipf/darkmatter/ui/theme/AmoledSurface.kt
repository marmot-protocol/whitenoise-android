package dev.ipf.darkmatter.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
    if (isAmoledSurfaceTheme()) {
        border(BorderStroke(width, MaterialTheme.colorScheme.outlineVariant), shape)
    } else {
        this
    }
