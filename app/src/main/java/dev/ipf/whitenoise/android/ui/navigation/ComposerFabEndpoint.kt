package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ControlMorphSurface
import dev.ipf.whitenoise.android.ui.common.LocalControlHandoffEndpointCurrent
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.common.controlMorphSurface

/** The shell connects only these two real controls; no composer or draft state is shared. */
internal enum class ComposerFabEndpoint { Composer, NewMessage }

internal val LocalComposerFabHandoff = staticCompositionLocalOf<MeasuredControlHandoff?> { null }

/** The live editor keeps its owner; the pen's decorative glyph travels with the measured container. */
@Composable
internal fun Modifier.composerFabSurface(
    endpoint: ComposerFabEndpoint,
    fill: Color,
    border: Color?,
    radiusDp: Float,
    morphing: Boolean,
): Modifier {
    if (!LocalControlHandoffEndpointCurrent.current) return clearAndSetSemantics { }.drawWithContent { }
    val density = LocalDensity.current
    return controlMorphSurface(
        LocalComposerFabHandoff.current,
        endpoint,
        ControlMorphSurface(
            fill,
            border,
            with(density) { radiusDp.dp.toPx() },
            with(density) { 1.dp.toPx() },
            decorationAlpha = if (endpoint == ComposerFabEndpoint.NewMessage) 1f else 0f,
        ),
        nativeChromeSettled = !morphing,
    )
}

/** Keeps editor and sibling actions on the container clock without animating their input owner. */
@Composable
internal fun Modifier.composerFabContent(): Modifier {
    val state = LocalComposerFabHandoff.current
    val current = LocalControlHandoffEndpointCurrent.current
    return graphicsLayer { alpha = if (current) state?.renderedContentAlpha ?: 1f else 0f }
        .pointerInput(state, current) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (current && event.changes.any { it.pressed }) state?.finish()
                }
            }
        }
}

/** The painted grip follows the moving top border without moving its gesture or semantics owner. */
@Composable
internal fun Modifier.composerFabGrip(): Modifier {
    val state = LocalComposerFabHandoff.current
    val current = LocalControlHandoffEndpointCurrent.current
    return graphicsLayer {
        alpha = if (current) state?.renderedContentAlpha ?: 1f else 0f
        val moving = state?.renderedBounds
        val target = state?.targetBounds
        translationX = if (moving != null && target != null) moving.center.x - target.center.x else 0f
        translationY = if (moving != null && target != null) moving.top - target.top else 0f
    }
}

/** Paints one noninteractive pen above the host's chrome, outside each endpoint's local clip. */
@Composable
internal fun Modifier.composerFabPenDecoration(state: MeasuredControlHandoff): Modifier {
    val painter = painterResource(R.drawable.ic_edit)
    val tint = MaterialTheme.colorScheme.onSurface
    var origin by remember(state) { mutableStateOf(Offset.Zero) }
    return onPlaced { origin = it.positionInWindow() }
        .drawWithContent {
            drawContent()
            if (state.presentationActive) {
                val alpha = state.presentationDecorationAlpha
                state.presentationBounds?.takeIf { alpha > 0f }?.let { bounds ->
                    val center = bounds.center - origin
                    val iconSize = 24.dp.toPx()
                    translate(center.x - iconSize / 2f, center.y - iconSize / 2f) {
                        with(painter) {
                            draw(Size(iconSize, iconSize), alpha = alpha, colorFilter = ColorFilter.tint(tint))
                        }
                    }
                }
            }
        }
}

/** The route body may fade/travel subtly; live bottom controls remain outside that render layer. */
internal data class ConversationBodyMotion(
    val visibility: State<Float>,
    val conversation: Boolean,
    val direction: Int,
    val suppressed: Boolean,
)

internal val LocalConversationBodyMotion = staticCompositionLocalOf<ConversationBodyMotion?> { null }

/** Applied to list/transcript content only, never the editor, pen button or shell dictation actions. */
@Composable
internal fun Modifier.conversationRouteBodyMotion(): Modifier {
    val motion = LocalConversationBodyMotion.current ?: return this
    val travel = with(LocalDensity.current) { 8.dp.toPx() }
    return graphicsLayer {
        alpha = conversationRouteLayerAlpha(motion.visibility.value, motion.conversation, motion.suppressed)
        translationX =
            travel * motion.direction *
            conversationRouteLayerTranslation(motion.visibility.value, motion.conversation, motion.suppressed)
    }
}
