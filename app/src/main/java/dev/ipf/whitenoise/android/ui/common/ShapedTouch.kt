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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

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
 * Takes the touch for a control, without drawing any highlight itself. Pair it with [shapedPressFeedback]
 * on the visible shape inside, using the same [interactionSource]. It asks for at least [minSize] square,
 * but cannot grow past a size an earlier modifier fixed. To keep a smaller layout slot while the target
 * grows, put [touchTargetOverhang] first.
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
 * Keeps a control's layout slot at [slotWidth] by [slotHeight] while its touch target keeps its own measured
 * size, at least [MinimumTouchTarget] once [shapedTouchTarget] follows, centred over the slot and reaching
 * into the space around it. A floating control keeps the place it always had in its stack, and only its
 * target grows. Put it first in the chain, so every later modifier sees the full target. An unspecified side
 * takes the target's own size.
 */
internal fun Modifier.touchTargetOverhang(
    slotWidth: Dp = Dp.Unspecified,
    slotHeight: Dp = Dp.Unspecified,
): Modifier =
    layout { measurable, constraints ->
        val target = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = if (slotWidth.isSpecified) slotWidth.roundToPx() else target.width
        val height = if (slotHeight.isSpecified) slotHeight.roundToPx() else target.height
        val slot = IntSize(constraints.constrainWidth(width), constraints.constrainHeight(height))
        layout(slot.width, slot.height) {
            target.placeRelative((slot.width - target.width) / 2, (slot.height - target.height) / 2)
        }
    }

/**
 * Clips to [shape] and draws the press highlight of [interactionSource] inside it, so a tap on the larger
 * [shapedTouchTarget] lights up the circle or pill the user sees instead of the square around it.
 */
internal fun Modifier.shapedPressFeedback(
    interactionSource: MutableInteractionSource,
    shape: Shape,
): Modifier = clip(shape).indication(interactionSource, ripple())

/**
 * Draws the press highlight of [interactionSource] on content that already sits inside its shape's clip, such
 * as a Surface's content. Clipping again there would thin the Surface's antialiased outline, so this draws the
 * highlight without a clip of its own. Pair it with a click that has `indication = null`.
 */
internal fun Modifier.pressFeedbackInsideClip(interactionSource: MutableInteractionSource): Modifier {
    val highlight = ripple()
    return indication(interactionSource, highlight)
}

/**
 * A circle as wide as the shorter side, centred in its bounds. [SlotIconButton] draws its highlight and fill
 * on it, so a slot that is not square still shows a round highlight. Don't hand it to an `IconButton`: that
 * clip also limits where the button takes touches.
 */
internal val CenteredCircleShape: Shape =
    GenericShape { size, _ ->
        val radius = minOf(size.width, size.height) / 2f
        addOval(Rect(Offset(size.width / 2f, size.height / 2f), radius))
    }
