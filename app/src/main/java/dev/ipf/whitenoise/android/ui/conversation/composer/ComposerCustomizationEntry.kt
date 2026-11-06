package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R

/** Observe at Initial so a long press cancels the child's normal click, including trusted-system Paste. */
@Composable
internal fun Modifier.composerCustomizationEntry(
    enabled: Boolean,
    onCustomize: (() -> Unit)?,
): Modifier {
    val latestCustomize by rememberUpdatedState(onCustomize)
    val label = stringResource(R.string.composer_customize)
    if (!enabled || onCustomize == null) return this
    return semantics {
        customActions =
            listOf(
                CustomAccessibilityAction(label) {
                    latestCustomize?.invoke()
                    true
                },
            )
    }.pointerInput(enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val released =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        finished = change == null ||
                            !change.pressed ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    }
                    true
                }
            if (released == null) {
                latestCustomize?.invoke()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}
