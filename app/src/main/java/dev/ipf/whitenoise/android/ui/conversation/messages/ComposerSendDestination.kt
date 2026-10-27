package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendMotion

/** Tags the actual measured surface without adding a row, layout wrapper or click target. */
internal fun Modifier.composerSendDestination(
    presentationId: String,
    motion: ComposerSendMotion?,
    presentation: BubblePresentation,
    mine: Boolean,
): Modifier =
    composed {
        val measuredSurface = testTag(composerSendDestinationTag(presentationId))
        if (motion == null) return@composed measuredSurface
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val shape = MaterialTheme.shapes.large
        val border = messageBubbleBorder(false, mine, presentation.suppressBorder, presentation.borderOverrideArgb)
        val borderColor = (border?.brush as? SolidColor)?.value ?: Color.Transparent
        measuredSurface
            .onGloballyPositioned { coordinates ->
                if (motion.activePresentationId != presentationId) return@onGloballyPositioned
                val outline =
                    shape.createOutline(
                        androidx.compose.ui.geometry
                            .Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
                        direction,
                        density,
                    )
                val corners = (outline as? Outline.Rounded)?.roundRect
                if (corners == null) {
                    if (motion.activePresentationId == presentationId) motion.clear()
                    return@onGloballyPositioned
                }
                motion.destinationFrame(
                    presentationId,
                    coordinates,
                    colorFromArgb(presentation.backgroundArgb),
                    borderColor,
                    corners,
                    with(density) { border?.width?.toPx() ?: 0f },
                )
            }.graphicsLayer { alpha = if (motion.presentation(presentationId).coversNative) 0f else 1f }
    }

/** Identifies the actual landing surface before, during and after its accepted copy flight. */
internal fun composerSendDestinationTag(presentationId: String): String = "composer.send.destination.$presentationId"
