@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * One terminal text-bubble footer: the full warning precedes the timestamp metadata. Reserve the
 * metadata's width before wrapping the warning, and align its last baseline with the timestamp.
 * At widths that cannot fit even one warning word beside metadata, stack the warning above it.
 * A bubble without a warning keeps its existing compact inline metadata.
 */
@Composable
internal fun MessageBubbleMetadataFooter(
    warning: String?,
    color: Color,
    metadata: @Composable () -> Unit,
) {
    if (warning == null) {
        metadata()
        return
    }
    Layout(content = {
        MessageBubbleInvalidationWarning(warning = warning, color = color)
        Box { metadata() }
    }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val metadataPlaceable = measurables[1].measure(loose)
        val gap = if (metadataPlaceable.width == 0) 0 else 4.dp.roundToPx()
        // Intrinsic measurement can supply Infinity; subtracting from that sentinel creates an
        // unrepresentable finite Constraints width (reply previews ask for intrinsic dimensions).
        val remainingWidth =
            if (constraints.hasBoundedWidth) {
                (constraints.maxWidth - metadataPlaceable.width - gap).coerceAtLeast(0)
            } else {
                Constraints.Infinity
            }
        val stacked = measurables[0].minIntrinsicWidth(Constraints.Infinity) > remainingWidth
        val warningPlaceable =
            measurables[0].measure(loose.copy(maxWidth = if (stacked) constraints.maxWidth else remainingWidth))
        val metadataBaseline =
            metadataPlaceable[LastBaseline].takeIf { it != AlignmentLine.Unspecified }
                ?: metadataPlaceable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified }
                ?: metadataPlaceable.height
        val warningBaseline =
            warningPlaceable[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: warningPlaceable.height
        val baseline =
            if (stacked) warningPlaceable.height + gap + metadataBaseline else maxOf(warningBaseline, metadataBaseline)
        val warningY = if (stacked) 0 else baseline - warningBaseline
        val metadataY = baseline - metadataBaseline
        val width =
            (
                if (stacked) {
                    maxOf(warningPlaceable.width, metadataPlaceable.width)
                } else {
                    warningPlaceable.width + gap + metadataPlaceable.width
                }
            ).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height =
            maxOf(warningY + warningPlaceable.height, metadataY + metadataPlaceable.height)
                .coerceIn(constraints.minHeight, constraints.maxHeight)
        val firstBaseline =
            minOf(
                warningPlaceable.positionedFirstBaseline(warningY, baseline),
                metadataPlaceable.positionedFirstBaseline(metadataY, baseline),
            )
        layout(width, height, mapOf(FirstBaseline to firstBaseline, LastBaseline to baseline)) {
            warningPlaceable.placeRelative(0, warningY)
            metadataPlaceable.placeRelative(width - metadataPlaceable.width, metadataY)
        }
    }
}

private fun Placeable.positionedFirstBaseline(
    y: Int,
    fallback: Int,
): Int = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified }?.let { y + it } ?: fallback
