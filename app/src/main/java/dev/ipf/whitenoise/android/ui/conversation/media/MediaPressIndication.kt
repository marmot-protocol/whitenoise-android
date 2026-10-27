package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.launch

private const val MEDIA_PRESSED_ALPHA = 0.12f
private const val MEDIA_HOVERED_ALPHA = 0.08f

/** Limits the viewer's lighter feedback to motion-enabled media, leaving other dialogs unchanged. */
internal val LocalMediaViewerPressFeedback = staticCompositionLocalOf { false }

/** Ready image controls use a draw-only state layer inside a motion-enabled thumbnail host. */
@Composable
internal fun rememberMediaViewerPressIndication(ready: Boolean = true): Indication =
    if (ready && LocalMediaViewerThumbnails.current != null) rememberMediaPressIndication() else LocalIndication.current

/** Stable draw-only feedback; color updates do not replace a focused or pressed interaction node. */
@Composable
internal fun rememberMediaPressIndication(circle: Boolean = false): IndicationNodeFactory {
    val color = rememberUpdatedState(LocalContentColor.current)
    return remember(color, circle) { MediaPressIndication(color, circle) }
}

/** Keeps the standard button's semantics, hit target and disabled behavior without a second moving ripple. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming") // Compose components use UpperCamelCase.
internal fun MediaViewerCloseButton(
    onDismiss: () -> Unit,
    enabled: Boolean,
) {
    val source = remember { MutableInteractionSource() }
    val lightweight = LocalMediaViewerPressFeedback.current
    val feedback = rememberMediaPressIndication(circle = true)
    val modifier = Modifier.size(48.dp).then(if (lightweight) Modifier.indication(source, feedback) else Modifier)
    val ripple = if (lightweight) null else LocalRippleConfiguration.current
    CompositionLocalProvider(LocalRippleConfiguration provides ripple) {
        IconButton(onClick = onDismiss, enabled = enabled, modifier = modifier, interactionSource = source) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
        }
    }
}

/** Per-control state never escapes its interaction source or retains media pixels. */
private data class MediaPressIndication(
    val color: State<Color>,
    val circle: Boolean,
) : IndicationNodeFactory {
    /** Each clickable owns its interaction sets while the factory shares only current theme color. */
    override fun create(interactionSource: InteractionSource) = MediaPressNode(interactionSource, color, circle)
}

/** A static state layer avoids RippleDrawable shader creation and its continuing hardware animation. */
private class MediaPressNode(
    val source: InteractionSource,
    val color: State<Color>,
    val circle: Boolean,
) : Modifier.Node(),
    DrawModifierNode {
    private val presses = mutableSetOf<PressInteraction.Press>()
    private val focuses = mutableSetOf<FocusInteraction.Focus>()
    private val hovers = mutableSetOf<HoverInteraction.Enter>()

    /** Redraw cleared reused content, then collect changes without a timer or platform drawable. */
    override fun onAttach() {
        invalidateDraw()
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses.add(interaction)
                    is PressInteraction.Release -> presses.remove(interaction.press)
                    is PressInteraction.Cancel -> presses.remove(interaction.press)
                    is FocusInteraction.Focus -> focuses.add(interaction)
                    is FocusInteraction.Unfocus -> focuses.remove(interaction.focus)
                    is HoverInteraction.Enter -> hovers.add(interaction)
                    is HoverInteraction.Exit -> hovers.remove(interaction.enter)
                }
                invalidateDraw()
            }
        }
    }

    /** A reused node must not paint an old control's pressed, focus or hover state. */
    override fun onDetach() {
        presses.clear()
        focuses.clear()
        hovers.clear()
    }

    /** Draw the original content and a bounded state layer only while an interaction is active. */
    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha =
            when {
                presses.isNotEmpty() -> MEDIA_PRESSED_ALPHA
                focuses.isNotEmpty() -> MEDIA_PRESSED_ALPHA
                hovers.isNotEmpty() -> MEDIA_HOVERED_ALPHA
                else -> 0f
            }
        if (alpha > 0f) {
            val tint = color.value.copy(alpha = alpha)
            if (circle) drawCircle(tint) else drawRect(tint)
        }
    }
}
