@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/** How a close gesture ends: a settled sheet records the declines, a failed close is left unsaved. */
internal enum class PromptCloseMode { RECORD, LEAVE_UNSAVED }

/**
 * Which close the sheet currently allows, or null while it is pinned by work in flight.
 *
 * A settled receipt records the declines. A failed close may be left unsaved: nothing is written,
 * so collection stays off and the next launch asks again. A retry in flight still pins the sheet.
 */
internal fun promptCloseMode(
    idle: Boolean,
    receiptSettled: Boolean,
    closeFailed: Boolean,
): PromptCloseMode? =
    when {
        !idle -> null
        closeFailed -> PromptCloseMode.LEAVE_UNSAVED
        receiptSettled -> PromptCloseMode.RECORD
        else -> null
    }

/** Survives recomposition so a second tap or a removed presentation cannot start another close. */
internal class PromptCloseState {
    var closing by mutableStateOf(false)
    var mode by mutableStateOf<PromptCloseMode?>(null)
}

/**
 * Returns the close action for the current [mode], or a no-op while the sheet is pinned.
 *
 * The sheet closes on the gesture and records the declined choices afterwards. Waiting for one or
 * two native writes first made a slow write read as "the X did nothing", and invited a second tap
 * that re-entered the whole thing, so `closing` makes the second tap a no-op instead.
 */
@Composable
internal fun rememberPromptFinish(
    close: PromptCloseState,
    hide: suspend () -> Unit,
    mode: PromptCloseMode?,
    onCloseStarted: () -> Unit,
    onDone: () -> Unit,
    onDismissFailedClose: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val onDoneNow by rememberUpdatedState(onDone)
    val onDismissFailedCloseNow by rememberUpdatedState(onDismissFailedClose)
    DisposableEffect(Unit) {
        onDispose {
            // A lock/overlay can remove the sheet before its hide animation finishes.
            // The runtime owner and its native save outlive this presentation scope.
            when (close.mode) {
                PromptCloseMode.RECORD -> onDoneNow()
                PromptCloseMode.LEAVE_UNSAVED -> onDismissFailedCloseNow()
                null -> Unit
            }
        }
    }
    return {
        if (mode != null && !close.closing) {
            close.closing = true
            close.mode = mode
            if (mode == PromptCloseMode.RECORD) onCloseStarted()
            scope.launch {
                hide()
                if (mode == PromptCloseMode.RECORD) onDone() else onDismissFailedClose()
            }
        }
    }
}
