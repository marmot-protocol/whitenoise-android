@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Alpha of an icon on a disabled [SlotIconButton], as on Material's icon buttons. */
private const val DISABLED_ICON_ALPHA = 0.38f

/**
 * An icon button for a slot that may not be square, such as the composer's 36 by 48 dp toolbar slots
 * (#4102). An `IconButton` clips its highlight, its fill and its touch area to one shape, so in such a slot it
 * either draws a capsule or, given a circle, stops answering taps above and below it. Here the whole slot
 * answers taps, and the press highlight and [containerColor] are a circle as wide as the slot's shorter side,
 * centred in it. A slot sized by [modifier] keeps that size; an unsized one is at least 48 dp square.
 */
@Composable
internal fun SlotIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    contentColor: Color = LocalContentColor.current,
    content: @Composable () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.shapedTouchTarget(press, onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .shapedPressFeedback(press, CenteredCircleShape)
                .background(containerColor),
        )
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) contentColor else contentColor.copy(alpha = DISABLED_ICON_ALPHA),
            content = content,
        )
    }
}
