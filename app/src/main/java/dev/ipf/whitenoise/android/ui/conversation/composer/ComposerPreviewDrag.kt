@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.whitenoise.android.state.ComposerShortcut

/** Slots report geometry and leave a real hole under the glyph lifted by the stable preview parent. */
@Composable
internal fun composerPreviewDragModifier(
    shortcut: ComposerShortcut,
    state: ComposerToolbarEditState,
    @Suppress("UNUSED_PARAMETER") saving: Boolean,
): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    DisposableEffect(state, shortcut) { onDispose { state.forget(shortcut) } }
    return Modifier
        .onGloballyPositioned {
            state.previewRtl = rtl
            state.record(shortcut, it.boundsInRoot())
        }.drawWithContent {
            if (state.dragging != shortcut) drawContent()
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
