package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Material's widest dialog surface. */
private val DialogMaxWidth = 560.dp

/** The gap kept to each edge of the app window: content that fills its width leaves a 312 dp surface at 360 dp. */
private val DialogMinSideMargin = WhiteNoiseSpacing.Section

/** The widest a dialog surface may be over an app window [windowWidth] wide: 560 dp, and 24 dp clear of each edge. */
internal fun dialogMaxWidth(windowWidth: Dp): Dp {
    val insetWidth = (windowWidth - DialogMinSideMargin * 2).coerceAtLeast(0.dp)
    return minOf(DialogMaxWidth, insetWidth)
}

/**
 * Caps a dialog surface at [dialogMaxWidth] of the app window it opens over. Material only bounds the surface to
 * 280–560 dp, so content that fills its width reaches both screen edges wherever the dialog window spans the screen.
 * Android often narrows dialog windows already, so this is only a maximum: it never takes a second margin from a
 * window the platform has inset. [windowWidth] is the app window's width, read where the dialog is composed, not the
 * width the dialog window offers its content.
 */
internal fun Modifier.dialogWidthBounds(windowWidth: Dp): Modifier = widthIn(max = dialogMaxWidth(windowWidth))
