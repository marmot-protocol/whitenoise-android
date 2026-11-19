package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.connectedRowBorderPath
import kotlin.math.ceil

private const val NATIVE_SEAM_HALF_PIXEL = 0.5f
private const val QUARTER_TURN = 90f
private const val HALF_TURN = 180f
private const val THREE_QUARTER_TURN = 270f

// Resolved shape, native style and progress belong to this single draw.

/** Draw only native-weight chrome; absent protected pictures never imply an opaque sheet. */
@Suppress("LongParameterList", "LongMethod")
internal fun DrawScope.drawRouteContour(
    bubble: Rect,
    contour: RoundRect,
    path: Path,
    source: SettingsRouteEndpoint?,
    presence: Float,
    fallbackOutline: Color,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    val stroke = source?.outline ?: fallbackOutline
    if (stroke.isSpecified) {
        val width = ceil(1.dp.toPx())
        val inset = width / 2f
        if (source != null && presence == 0f) {
            // Match the native renderer before motion: one joined path, without a second AA clip.
            if (!source.last) {
                drawLine(
                    source.seam,
                    Offset(bubble.left + inset, bubble.bottom - NATIVE_SEAM_HALF_PIXEL),
                    Offset(bubble.right - inset, bubble.bottom - NATIVE_SEAM_HALF_PIXEL),
                    strokeWidth = 1f,
                    blendMode = blendMode,
                )
            }
            drawPath(
                connectedRowBorderPath(bubble, contour, source.first, source.last, width),
                stroke,
                style = Stroke(width),
                blendMode = blendMode,
            )
            return
        }

        fun CornerRadius.inset() = CornerRadius((x - inset).coerceAtLeast(0f), (y - inset).coerceAtLeast(0f))
        val edge =
            RoundRect(
                bubble.left + inset,
                bubble.top + inset,
                bubble.right - inset,
                bubble.bottom - inset,
                contour.topLeftCornerRadius.inset(),
                contour.topRightCornerRadius.inset(),
                contour.bottomRightCornerRadius.inset(),
                contour.bottomLeftCornerRadius.inset(),
            )
        // A departing connected segment closes its exposed edges continuously, retaining side weight.
        val full = Path().apply { addRoundRect(edge) }
        clipPath(path) {
            if (source == null || (source.first && source.last)) {
                drawPath(full, stroke, style = Stroke(width), blendMode = blendMode)
            } else {
                drawConnectedSegmentChrome(bubble, edge, source, presence, stroke, width, blendMode)
            }
        }
    }
}

/** Side endpoints remain independent for first, last and middle native rows. */
private fun connectedSegmentSides(
    bubble: Rect,
    edge: RoundRect,
    source: SettingsRouteEndpoint,
    presence: Float,
): Path {
    val tl = edge.topLeftCornerRadius.x
    val tr = edge.topRightCornerRadius.x
    val br = edge.bottomRightCornerRadius.x
    val bl = edge.bottomLeftCornerRadius.x
    return Path().apply {
        moveTo(edge.left, if (source.first || presence > 0f) edge.top + tl else bubble.top)
        lineTo(edge.left, if (source.last || presence > 0f) edge.bottom - bl else bubble.bottom)
        moveTo(edge.right, if (source.first || presence > 0f) edge.top + tr else bubble.top)
        lineTo(edge.right, if (source.last || presence > 0f) edge.bottom - br else bubble.bottom)
    }
}

// One resolved segment geometry and native paint style.

/** Paint exposed connected caps without changing the native side stroke weight. */
@Suppress("LongParameterList")
private fun DrawScope.drawConnectedSegmentChrome(
    bubble: Rect,
    edge: RoundRect,
    source: SettingsRouteEndpoint,
    presence: Float,
    stroke: Color,
    width: Float,
    blendMode: BlendMode,
) {
    // Native open segments acquire the target's rounded corners with their closing edges.
    // At presence=1 these paths exactly join the closed native target, even for a middle row.
    val tl = edge.topLeftCornerRadius.x
    val tr = edge.topRightCornerRadius.x
    val br = edge.bottomRightCornerRadius.x
    val bl = edge.bottomLeftCornerRadius.x

    fun Path.corner(
        rect: Rect,
        angle: Float,
    ) {
        if (rect.width > 0f) arcTo(rect, angle, QUARTER_TURN, false)
    }
    val sides = connectedSegmentSides(bubble, edge, source, presence)
    val top =
        Path().apply {
            moveTo(edge.left, edge.top + tl)
            corner(Rect(edge.left, edge.top, edge.left + 2 * tl, edge.top + 2 * tl), HALF_TURN)
            lineTo(edge.right - tr, edge.top)
            corner(Rect(edge.right - 2 * tr, edge.top, edge.right, edge.top + 2 * tr), THREE_QUARTER_TURN)
        }
    val bottom =
        Path().apply {
            moveTo(edge.right, edge.bottom - br)
            corner(Rect(edge.right - 2 * br, edge.bottom - 2 * br, edge.right, edge.bottom), 0f)
            lineTo(edge.left + bl, edge.bottom)
            corner(Rect(edge.left, edge.bottom - 2 * bl, edge.left + 2 * bl, edge.bottom), QUARTER_TURN)
        }
    if (!source.last) {
        drawLine(
            source.seam,
            Offset(edge.left, bubble.bottom - NATIVE_SEAM_HALF_PIXEL),
            Offset(edge.right, bubble.bottom - NATIVE_SEAM_HALF_PIXEL),
            alpha = 1f - presence,
            strokeWidth = 1f,
            blendMode = blendMode,
        )
    }
    drawPath(sides, stroke, style = Stroke(width), blendMode = blendMode)
    if (source.first || presence > 0f) {
        drawPath(top, stroke, alpha = if (source.first) 1f else presence, style = Stroke(width), blendMode = blendMode)
    }
    if (source.last || presence > 0f) {
        drawPath(
            bottom,
            stroke,
            alpha = if (source.last) 1f else presence,
            style = Stroke(width),
            blendMode = blendMode,
        )
    }
}
