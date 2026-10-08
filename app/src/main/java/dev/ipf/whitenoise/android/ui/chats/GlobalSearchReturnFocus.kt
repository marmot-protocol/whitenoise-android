package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/** Ends an off-window return at the viewport, even when its lazy item was never composed. */
@Composable
internal fun globalSearchReturnFocusExpiryEffect(
    owner: GlobalSearchSelectionOwner?,
    ready: Boolean,
    isVisible: () -> Boolean,
) {
    val visible = rememberUpdatedState(isVisible)
    val generation = owner?.returnGeneration
    LaunchedEffect(owner, generation, ready) {
        if (owner == null || generation == null || !ready) return@LaunchedEffect
        withFrameNanos { }
        if (!visible.value()) owner.consumeReturnFocus(generation)
    }
}

/** Restores an already-visible result's focus once; never scrolls or traps later user focus. */
@Composable
internal fun globalSearchReturnFocusModifier(
    restoredSelection: Boolean,
    returnGeneration: Long = 0L,
    consumeReturn: () -> Boolean = { true },
    isVisible: () -> Boolean,
): Modifier {
    val requester = remember { FocusRequester() }
    val visible = rememberUpdatedState(isVisible)
    val consume = rememberUpdatedState(consumeReturn)
    LaunchedEffect(restoredSelection, returnGeneration) {
        if (restoredSelection) {
            withFrameNanos { }
            // Consume even an offscreen/touch-mode attempt, so recycling cannot steal later user focus.
            if (consume.value() && visible.value()) requester.requestFocus()
        }
    }
    return Modifier.focusRequester(requester)
}
