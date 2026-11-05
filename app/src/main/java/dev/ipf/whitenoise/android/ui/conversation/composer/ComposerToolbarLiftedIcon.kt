@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import dev.ipf.whitenoise.android.state.ComposerShortcut
import kotlin.math.roundToInt

private const val COMPOSER_LIFTED_SHORTCUT_SCALE = 1.15f

/** Keeps the grab point attached to the finger, then settles into the actual laid-out slot. */
@Composable
internal fun BoxScope.ComposerToolbarLiftedIcon(state: ComposerToolbarEditState) {
    val released = state.release
    val shortcut = state.dragging ?: released?.shortcut ?: return
    val scale = remember(state, shortcut) { Animatable(1f) }
    val settling = remember(state, released) { Animatable(released?.from ?: Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(state, shortcut, state.dragging) {
        scale.animateTo(
            if (state.dragging != null) COMPOSER_LIFTED_SHORTCUT_SCALE else 1f,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        )
    }
    val target = released?.target
    LaunchedEffect(state, released, target) {
        if (released != null && target != null) {
            // Only geometry reported by the committed slot can start settling.
            settling.animateTo(
                target,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            )
            state.finishRelease(released)
        }
    }
    val diameter = with(LocalDensity.current) { ComposerShortcutTarget.toPx() }
    Box(
        Modifier
            .align(AbsoluteAlignment.TopLeft)
            .absoluteOffset {
                val absoluteCenter = if (state.dragging != null) state.dragCenter else settling.value
                val center = absoluteCenter - state.selectedArea.topLeft
                IntOffset((center.x - diameter / 2f).roundToInt(), (center.y - diameter / 2f).roundToInt())
            }.size(ComposerShortcutTarget)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }.testTag("composer.toolbar.lifted.${shortcut.id}"),
        contentAlignment = Alignment.Center,
    ) {
        if (shortcut == ComposerShortcut.VoiceNote) {
            MiniWaveformGlyph(MaterialTheme.colorScheme.primary)
        } else {
            Icon(
                painterResource(shortcut.iconRes),
                null,
                Modifier.size(ComposerShortcutIconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
