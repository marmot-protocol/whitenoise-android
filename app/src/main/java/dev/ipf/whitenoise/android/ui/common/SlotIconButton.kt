@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The default largest highlight circle, Material's icon button state layer. */
private val SlotIconButtonCircleMax = 40.dp

/** Alpha of an icon on a disabled [SlotIconButton], as on Material's icon buttons. */
private const val DISABLED_ICON_ALPHA = 0.38f

/**
 * An icon button for a slot that may not be square, such as the composer's 36 by 48 dp toolbar slots
 * (#4102). An `IconButton` clips its highlight, its fill and its touch area to one shape, so in such a slot it
 * either draws a capsule or, given a circle, stops answering taps above and below it. Here the whole slot
 * answers taps, and the press highlight and [containerColor] are a circle as wide as the slot's shorter side,
 * up to [highlightMax], centred in it. A slot sized by [modifier] keeps that size, and an unsized one is
 * 48 dp square with the 40 dp circle of an ordinary icon button.
 */
@Composable
internal fun SlotIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    contentColor: Color = LocalContentColor.current,
    highlightMax: Dp = SlotIconButtonCircleMax,
    content: @Composable () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    SlotIconButtonLayout(
        target = modifier.shapedTouchTarget(press, onClick = onClick, enabled = enabled),
        press = press,
        containerColor = containerColor,
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = DISABLED_ICON_ALPHA),
        highlightMax = highlightMax,
        content = content,
    )
}

/**
 * The toggle form of [SlotIconButton], for a slot that may not be square: the whole slot toggles, and a
 * checked button tints its icon with the primary colour, as Material's icon toggle button does.
 */
@Composable
internal fun SlotIconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val contentColor = if (checked) MaterialTheme.colorScheme.primary else LocalContentColor.current
    SlotIconButtonLayout(
        target =
            modifier
                .sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
                .toggleable(
                    value = checked,
                    interactionSource = press,
                    indication = null,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                ),
        press = press,
        containerColor = Color.Transparent,
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = DISABLED_ICON_ALPHA),
        highlightMax = SlotIconButtonCircleMax,
        content = content,
    )
}

/** The slot both buttons share: the touch on [target], a centred circle for the highlight and fill, the icon. */
@Composable
private fun SlotIconButtonLayout(
    target: Modifier,
    press: MutableInteractionSource,
    containerColor: Color,
    contentColor: Color,
    highlightMax: Dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = target, contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .sizeIn(maxWidth = highlightMax, maxHeight = highlightMax)
                    .fillMaxSize()
                    .shapedPressFeedback(press, CenteredCircleShape)
                    .background(containerColor),
            )
        }
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}
