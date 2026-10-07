package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/** Restores an already-visible result's focus once; never scrolls or traps later user focus. */
@Composable
internal fun globalSearchReturnFocusModifier(
    restoredSelection: Boolean,
    returnGeneration: Long = 0L,
    isVisible: () -> Boolean,
): Modifier {
    val requester = remember { FocusRequester() }
    val visible = rememberUpdatedState(isVisible)
    LaunchedEffect(restoredSelection, returnGeneration) {
        if (restoredSelection) {
            withFrameNanos { }
            if (visible.value()) requester.requestFocus()
        }
    }
    return Modifier.focusRequester(requester)
}
