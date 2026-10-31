package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.ControlMorphSurface
import dev.ipf.whitenoise.android.ui.common.LocalControlHandoffEndpointCurrent
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.common.controlMorphSurface

/** The shell connects only these two real controls; no composer or draft state is shared. */
internal enum class ComposerFabEndpoint { Composer, NewMessage }

internal val LocalComposerFabHandoff = staticCompositionLocalOf<MeasuredControlHandoff?> { null }

/** Only container artwork morphs; the one live editor/icon keeps its normal size and input owner. */
@Composable
internal fun Modifier.composerFabSurface(
    endpoint: ComposerFabEndpoint,
    fill: Color,
    border: Color?,
    radiusDp: Float,
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
        ),
    )
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
