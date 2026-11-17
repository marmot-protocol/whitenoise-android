package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * A short, stationary finger tap that no child handled reaches the editor (#3857). Runs on the
 * Main pass after every child, so text selection, scrolling, resize drags and enabled controls
 * keep their gestures; disabled controls opt out with [composerControlTarget]. The top resize
 * strip stays drag-only. Adds no semantics: TalkBack keeps the editor's own focus action.
 */
internal fun Modifier.composerBlankSurfaceTap(
    enabled: Boolean,
    onTap: () -> Unit,
): Modifier =
    composed {
        val latestOnTap by rememberUpdatedState(onTap)
        pointerInput(enabled) {
            if (!enabled) return@pointerInput
            val touchSlop = viewConfiguration.touchSlop
            val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                if (down.type != PointerType.Touch || down.position.y < ComposerResizeStripHeight.toPx()) {
                    return@awaitEachGesture
                }
                var last = down
                var cancelled = false
                do {
                    val event = awaitPointerEvent()
                    last = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    cancelled = cancelled ||
                        last.isConsumed ||
                        event.changes.size > 1 ||
                        (last.position - down.position).getDistance() > touchSlop ||
                        last.uptimeMillis - down.uptimeMillis >= longPressTimeoutMillis
                } while (last.pressed)
                if (!cancelled) {
                    last.consume()
                    latestOnTap()
                }
            }
        }
    }

/**
 * Claims the touch inside a control's target so the blank-surface fallback never acts there,
 * including while the control is disabled and therefore installs no click handler. Consumes the
 * down on the Main pass after the control's own handler, leaving its tap and hold behavior
 * unchanged. A disabled control claims the same laid-out area its enabled form responds to.
 */
internal fun Modifier.composerControlTarget(): Modifier = this then ComposerControlTargetElement

private data object ComposerControlTargetElement : ModifierNodeElement<ComposerControlTargetNode>() {
    override fun create() = ComposerControlTargetNode()

    override fun update(node: ComposerControlTargetNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "composerControlTarget"
    }
}

private class ComposerControlTargetNode :
    Modifier.Node(),
    PointerInputModifierNode {
    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (pass != PointerEventPass.Main) return
        pointerEvent.changes.forEach { if (it.changedToDownIgnoreConsumed()) it.consume() }
    }

    override fun onCancelPointerInput() = Unit
}
