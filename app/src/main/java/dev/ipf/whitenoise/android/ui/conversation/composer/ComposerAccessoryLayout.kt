package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/** Keeps accessory overflow scrollable while reserving at least half the finite viewport for input. */
internal fun Modifier.boundedComposerAccessory(): Modifier =
    layout { measurable, constraints ->
        val limit = if (constraints.hasBoundedHeight) constraints.maxHeight / 2 else constraints.maxHeight
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = limit))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
