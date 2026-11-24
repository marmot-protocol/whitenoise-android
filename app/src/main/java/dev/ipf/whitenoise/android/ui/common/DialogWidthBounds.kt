package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Material's widest dialog surface. */
private val DialogMaxWidth = 560.dp

/** The gap kept to each window edge: content that fills its width still leaves a 312 dp surface at 360 dp. */
private val DialogMinSideMargin = WhiteNoiseSpacing.Section

/**
 * Bounds a dialog surface to Material's widest dialog (560 dp) and keeps 24 dp to each window edge, on top of
 * Material's own 280–560 dp bounds. Those alone let content that fills its width reach both edges of a narrow phone.
 * It must be the first modifier on the dialog, where the incoming width is the window's: further in, a measured source
 * morph has already pinned the width and the margins would be taken twice.
 */
internal fun Modifier.dialogWindowWidthBounds(): Modifier =
    layout { measurable, constraints ->
        val maxWidth =
            if (constraints.hasBoundedWidth) {
                val margins = 2 * DialogMinSideMargin.roundToPx()
                minOf(DialogMaxWidth.roundToPx(), (constraints.maxWidth - margins).coerceAtLeast(0))
            } else {
                DialogMaxWidth.roundToPx()
            }
        val placeable =
            measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, maxWidth), maxWidth = maxWidth))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
