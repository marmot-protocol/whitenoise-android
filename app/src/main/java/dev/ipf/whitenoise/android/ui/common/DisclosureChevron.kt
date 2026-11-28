package dev.ipf.whitenoise.android.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens

private const val EXPANDED_ROTATION_DEGREES = 180f

/**
 * The one expand/collapse chevron. It points down when collapsed, turns up when expanded and follows the app's
 * motion curve, so every disclosure moves the same way. The row that owns it carries the state semantics.
 */
@Composable
@Suppress("FunctionNaming")
internal fun DisclosureChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val motionEnabled = rememberSystemAnimationsEnabled()
    val target = if (expanded) EXPANDED_ROTATION_DEGREES else 0f
    val rotation by animateFloatAsState(
        targetValue = target,
        animationSpec = if (motionEnabled) tween(APP_MOTION_CLOSE_MILLIS, easing = AppMotionOpenEasing) else snap(),
        label = "Disclosure chevron",
    )
    Icon(
        painterResource(R.drawable.ic_expand_more),
        contentDescription = null,
        modifier = modifier.size(Dimens.spaceXl).rotate(if (motionEnabled) rotation else target),
        tint = tint,
    )
}
