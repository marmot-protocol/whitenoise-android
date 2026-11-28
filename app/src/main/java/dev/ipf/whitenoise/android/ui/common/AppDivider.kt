package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.ipf.whitenoise.android.ui.theme.amoledRowSeamColor
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

/** Horizontal separator for app chrome, with the same muted AMOLED color as connected rows. */
@Suppress("FunctionNaming")
@Composable
internal fun AppDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    HorizontalDivider(modifier = modifier, color = if (isAmoledSurfaceTheme()) amoledRowSeamColor(color) else color)
}
