package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Picture
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.draw
import kotlin.math.ceil

/** Draws the content and records the same frame into [picture]. */
internal fun ContentDrawScope.drawAndRecord(picture: Picture) {
    val width = ceil(size.width).toInt()
    val height = ceil(size.height).toInt()
    if (width <= 0 || height <= 0) {
        drawContent()
        return
    }
    val recording = Canvas(picture.beginRecording(width, height))
    try {
        draw(this, layoutDirection, recording, size) { this@drawAndRecord.drawContent() }
    } finally {
        picture.endRecording()
    }
    // Nested RenderNode invalidations must reach the live canvas, not a replayed Picture.
    // Retain the inert copy only for the outgoing transition.
    drawContent()
}
