package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

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
) {
    val outline = amoledOutlineBorder()
    IconButton(onClick = onClick, modifier = Modifier.width(40.dp).height(48.dp)) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = if (outline == null) containerColor else MaterialTheme.colorScheme.surface,
            contentColor = if (outline == null) contentColor else MaterialTheme.colorScheme.onSurface,
            border = outline,
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
