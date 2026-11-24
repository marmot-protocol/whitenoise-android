@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.shapedPressFeedback
import dev.ipf.whitenoise.android.ui.common.shapedTouchTarget
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseElevation

/** Diameter of the visible disc of every floating control. */
internal val FloatingControlDiscSize = 34.dp

/** Size of the glyph inside a [FloatingControlDisc]. */
internal val FloatingControlIconSize = 16.dp

/** Height of the visible pill of a floating control that carries text, such as the mention chip. */
internal val FloatingControlPillHeight = FloatingControlDiscSize

/**
 * Frame the badge hangs from: it keeps a badge's corner the same small distance outside the disc that the
 * jump control's badge always had, whatever the size of the touch target around it.
 */
private val FloatingControlBadgeFrame = 42.dp

internal const val FLOATING_CONTROL_DISC_TAG = "floating_control.disc"

/** The colour roles every floating disc shares. */
internal object FloatingControlColors {
    /** The container of a floating disc. */
    val container: Color
        @Composable get() = MaterialTheme.colorScheme.secondaryContainer

    /** The glyph colour on [container]. */
    val content: Color
        @Composable get() = MaterialTheme.colorScheme.onSecondaryContainer
}

/**
 * The one floating control (#4065) — jump to newest, jump to top, resume read-aloud follow: a
 * [FloatingControlDiscSize] disc with a [FloatingControlIconSize] glyph inside a 48 dp touch target, a
 * press highlight clipped to the disc, and the [WhiteNoiseElevation.FloatingControl] shadow at rest and
 * pressed. [badge] hangs from the disc's top end corner and adds nothing to the control's own description;
 * describe the count in [contentDescription] instead.
 */
@Composable
internal fun FloatingControlDisc(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    container: Color = FloatingControlColors.container,
    content: Color = FloatingControlColors.content,
    badge: (@Composable BoxScope.() -> Unit)? = null,
) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .semantics { this.contentDescription = contentDescription }
                .shapedTouchTarget(press, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(FloatingControlBadgeFrame), contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = container,
                contentColor = content,
                shadowElevation = WhiteNoiseElevation.FloatingControl,
                modifier = Modifier.size(FloatingControlDiscSize).testTag(FLOATING_CONTROL_DISC_TAG),
            ) {
                // Inside the Surface, so its clip shapes the highlight without clipping the shadow away.
                Box(Modifier.fillMaxSize().shapedPressFeedback(press, CircleShape), Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = iconModifier.size(FloatingControlIconSize))
                }
            }
            badge?.invoke(this)
        }
    }
}
