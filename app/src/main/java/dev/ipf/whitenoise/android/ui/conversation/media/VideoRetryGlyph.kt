package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.shapedPressFeedback

/**
 * The retry glyph on a failed video disc. The whole 28 dp box takes the tap and only the press highlight is round,
 * because a clip before the click would also trim the tap area to the circle.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun VideoRetryGlyph(
    onRetry: () -> Unit,
    enabled: Boolean = true,
) {
    val press = remember { MutableInteractionSource() }
    Icon(
        painterResource(R.drawable.ic_refresh),
        contentDescription = stringResource(R.string.video_failed),
        modifier =
            Modifier
                .size(28.dp)
                .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onRetry)
                .shapedPressFeedback(press, CircleShape),
    )
}
