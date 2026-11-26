package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/** Paints the retained route body below both measured headers, excluding old chrome. */
internal fun DrawScope.drawFrozenSettingsBody(
    frame: PresentedFrame,
    chrome: Path,
    alpha: Float,
    clipTop: Float,
) {
    // The source and destination's measured body edges leave both native headers untouched.
    clipRect(top = clipTop) {
        clipPath(frame.body) {
            clipPath(chrome, ClipOp.Difference) {
                drawIntoCanvas { canvas ->
                    val layered = alpha < 1f
                    if (layered) canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = alpha })
                    canvas.nativeCanvas.drawPicture(frame.picture)
                    if (layered) canvas.restore()
                }
            }
        }
    }
}
