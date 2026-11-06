package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/** Measures the existing commands and retained recovery label in their original Layout scope. */
internal fun MeasureScope.measurePersistentDictation(
    measurables: List<Measurable>,
    constraints: Constraints,
    embedded: Boolean,
): MeasureResult {
    // Measure floating breathing room into the same endpoint used by the control morph.
    val horizontalInset = if (embedded) 0 else minOf(8.dp.roundToPx(), constraints.maxWidth / 2)
    val actions =
        measurables.last().measure(
            constraints.copy(minWidth = 0, minHeight = 0).offset(horizontal = -horizontalInset * 2),
        )
    // The former Row.widthIn(max = 288.dp) used Text.weight(1f, fill = true).
    // Preserve that finite constrained width rather than sizing to a short status string.
    val persistentWidth = minOf(constraints.maxWidth, 288.dp.roundToPx())
    val labelWidth =
        ((if (embedded) 288.dp.roundToPx() else persistentWidth - horizontalInset * 2) - actions.width).coerceAtLeast(0)
    val label =
        measurables.takeIf { it.size > 1 }?.first()?.measure(
            constraints.copy(
                minWidth = labelWidth,
                minHeight = 0,
                // Retained recovery text keeps its weighted allocation while decorative
                // clipping/opacity follows the existing clock. It owns no input target.
                maxWidth = labelWidth,
            ),
        )
    val width =
        constraints.constrainWidth(
            if (!embedded && label != null) persistentWidth else actions.width + horizontalInset * 2,
        )
    val height =
        constraints.constrainHeight(if (embedded) actions.height else maxOf(actions.height, label?.height ?: 0))
    return layout(width, height) {
        actions.placeRelative(width - horizontalInset - actions.width, (height - actions.height) / 2)
        label?.placeRelative(width - horizontalInset - actions.width - label.width, (height - label.height) / 2)
    }
}

/** Keeps moving chrome reads inside drawing and preserves the following accessibility semantics. */
internal fun Modifier.persistentDictationChrome(
    fill: Color,
    border: Color,
    status: String,
    chromeVisibility: () -> Float,
    movingChromeSize: () -> Size?,
): Modifier =
    drawWithCache {
        val radius = CornerRadius(24.dp.toPx())
        val strokeWidth = 1.dp.toPx()
        val stroke = Stroke(strokeWidth)
        val inset = Offset(strokeWidth / 2f, strokeWidth / 2f)
        onDrawWithContent {
            // Width/height are read in drawing, never while measuring the live commands.
            val chrome = movingChromeSize() ?: size
            val left = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width - chrome.width
            val top = (size.height - chrome.height) / 2f
            val visibility = chromeVisibility().coerceIn(0f, 1f)
            if (visibility > 0f) {
                drawRoundRect(
                    fill.copy(alpha = fill.alpha * visibility),
                    topLeft = Offset(left, top),
                    size = chrome,
                    cornerRadius = radius,
                )
                drawRoundRect(
                    border.copy(alpha = border.alpha * visibility),
                    topLeft = Offset(left + inset.x, top + inset.y),
                    size =
                        Size(
                            (chrome.width - strokeWidth).coerceAtLeast(0f),
                            (chrome.height - strokeWidth).coerceAtLeast(0f),
                        ),
                    cornerRadius = radius,
                    style = stroke,
                )
            }
            clipRect(left, top, left + chrome.width, top + chrome.height) { this@onDrawWithContent.drawContent() }
        }
    }.semantics {
        liveRegion = LiveRegionMode.Polite
        stateDescription = status
    }
