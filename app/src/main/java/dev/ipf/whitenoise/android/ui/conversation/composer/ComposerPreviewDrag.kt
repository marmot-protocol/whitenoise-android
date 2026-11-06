@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ComposerShortcut

/** Slots report geometry and leave a real hole under the glyph lifted by the stable preview parent. */
@Composable
internal fun composerPreviewDragModifier(
    shortcut: ComposerShortcut,
    state: ComposerToolbarEditState,
): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val released = state.release
    val targetAlpha by animateFloatAsState(
        if (state.dragging == shortcut && state.insertion != null) 1f else 0f,
        tween(120),
        label = "composer shortcut placement target",
    )
    val targetColor = MaterialTheme.colorScheme.primary
    DisposableEffect(state, shortcut) { onDispose { state.forget(shortcut) } }
    return Modifier
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
                // Reading release here invalidates placement even if the committed projection is unchanged.
                state.release?.let { state.placed(shortcut, it) }
            }
        }.onGloballyPositioned {
            state.previewRtl = rtl
            state.record(shortcut, it.boundsInRoot(), released)
        }.drawWithContent {
            if (state.dragging != shortcut && state.release?.shortcut != shortcut) drawContent()
            if (targetAlpha > 0f) {
                val radius = CornerRadius(size.minDimension / 2f)
                drawRoundRect(targetColor.copy(alpha = 0.10f * targetAlpha), cornerRadius = radius)
                drawRoundRect(
                    targetColor.copy(alpha = 0.65f * targetAlpha),
                    cornerRadius = radius,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
}

/** Gesture ownership does not move when the preview reflows or an icon crosses the text field. */
@Composable
internal fun Modifier.composerPreviewGestures(
    state: ComposerToolbarEditState?,
    saving: Boolean,
): Modifier {
    val currentSaving by rememberUpdatedState(saving)
    return if (state == null) {
        this
    } else {
        pointerInput(state) {
            try {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        if (!currentSaving) state.startNearest(state.selectedArea.topLeft + position)
                    },
                    onDrag = { change, delta ->
                        if (!currentSaving && state.dragging != null) {
                            state.drag(delta)
                            change.consume()
                        } else {
                            state.cancelDrag()
                        }
                    },
                    onDragEnd = { if (!currentSaving) state.drop() else state.cancelDrag() },
                    onDragCancel = state::cancelDrag,
                )
            } finally {
                state.cancelDrag()
            }
        }
    }
}
