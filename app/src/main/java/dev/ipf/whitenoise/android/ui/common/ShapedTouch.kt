package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.ripple
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Press feedback that follows the visible shape (#4102, #4065). A round or pill control often draws a small
 * shape inside a larger touch box, so the box takes the touch with no highlight of its own and the shape
 * draws the highlight on its own clip. Both halves share one interaction source:
 *
 *     val press = remember { MutableInteractionSource() }
 *     Box(Modifier.shapedTouchTarget(press, onClick = …), contentAlignment = Alignment.Center) {
 *         Box(Modifier.size(34.dp).shapedPressFeedback(press, CircleShape).background(…))
 *     }
 *
 * A control whose touch box is its visible shape uses `Surface(onClick = …, shape = …)` instead.
 * `ShapedTouchFeedbackCoverageTest` rejects the patterns that draw a rectangle around a round shape.
 */

/** The smallest touch target a control may have, from the Material and Android accessibility guidance. */
internal val MinimumTouchTarget = 48.dp

/**
 * Takes the touch for a control at least [minSize] square, without drawing any highlight itself. Pair it
 * with [shapedPressFeedback] on the visible shape inside, using the same [interactionSource].
 */
internal fun Modifier.shapedTouchTarget(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    minSize: Dp = MinimumTouchTarget,
): Modifier =
    sizeIn(minWidth = minSize, minHeight = minSize)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )

/**
 * Clips to [shape] and draws the press highlight of [interactionSource] inside it, so a tap on the larger
 * [shapedTouchTarget] lights up the circle or pill the user sees instead of the square around it.
 */
internal fun Modifier.shapedPressFeedback(
    interactionSource: MutableInteractionSource,
    shape: Shape,
): Modifier = clip(shape).indication(interactionSource, ripple())

/**
 * A circle as wide as the shorter side, centred in its bounds. A circle clip on a slot that is not square
 * stretches into a capsule; give an `IconButton` in a fixed-width composer slot this shape instead, so its
 * press highlight stays a circle while the whole slot still takes the touch.
 */
internal val CenteredCircleShape: Shape =
    GenericShape { size, _ ->
        val radius = minOf(size.width, size.height) / 2f
        addOval(Rect(Offset(size.width / 2f, size.height / 2f), radius))
    }
