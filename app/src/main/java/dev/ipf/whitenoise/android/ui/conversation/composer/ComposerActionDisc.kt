package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

private const val DISABLED_ACTION_ALPHA = 0.38f

/**
 * The prototype's send disc: a flat 32dp circle centred in the 40dp trailing slot, with the account's action
 * colours (the AMOLED outline theme paints the surface with an outline instead).
 */
@Composable
@Suppress("FunctionNaming")
internal fun ComposerActionDisc(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    description: String,
    @DrawableRes icon: Int,
    enabled: Boolean = true,
    width: Dp = ComposerToolbarSlotWidth,
    modifier: Modifier = Modifier,
) {
    val outline = amoledOutlineBorder()
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ACTION_ALPHA)
    val surfaceColor = if (outline == null) containerColor else MaterialTheme.colorScheme.surface
    val iconColor = if (outline == null) contentColor else MaterialTheme.colorScheme.onSurface
    ComposerToolbarTargets {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.width(width).height(48.dp)) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = if (enabled) surfaceColor else MaterialTheme.colorScheme.surface,
                contentColor = if (enabled) iconColor else disabledColor,
                border = if (enabled) outline else BorderStroke(1.dp, disabledColor),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = description,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
