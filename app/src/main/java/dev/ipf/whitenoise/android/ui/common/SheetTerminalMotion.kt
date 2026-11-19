package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Completes an accepted result after Material hides its existing sheet; it owns no animation clock. */
@OptIn(ExperimentalMaterial3Api::class)
internal class SheetTerminalMotion(
    private val sheet: SheetState,
    private val scope: CoroutineScope,
) {
    private var active = true
    private var accepted by mutableStateOf(false)

    val acceptsInput: Boolean get() = active && !accepted
    private var job: Job? = null

    fun close(action: () -> Unit) = closeWhenReady({ true }, action)

    /** Waits for the caller's composed busy veto to clear before Material hide. */
    fun closeWhenReady(
        ready: () -> Boolean,
        action: () -> Unit,
    ) {
        if (!active || accepted) return
        accepted = true
        job =
            scope.launch {
                var completed = false
                try {
                    snapshotFlow { ready() }.first { it }
                    if (sheet.currentValue != SheetValue.Hidden || sheet.targetValue != SheetValue.Hidden) sheet.hide()
                    if (active && !sheet.isVisible && sheet.targetValue == SheetValue.Hidden) {
                        completed = true
                        action()
                    }
                } finally {
                    // A veto or cancelled hide never commits the result and leaves a current owner retryable.
                    if (!completed) accepted = false
                }
            }
    }

    fun <T> close(
        value: T,
        action: (T) -> Unit,
    ) = close { action(value) }

    fun dispose() {
        active = false
        job?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberSheetTerminalMotion(
    sheet: SheetState,
    ownerKey: Any? = sheet,
): SheetTerminalMotion {
    val scope = rememberCoroutineScope()
    val terminal = remember(sheet, ownerKey) { SheetTerminalMotion(sheet, scope) }
    DisposableEffect(terminal) { onDispose { terminal.dispose() } }
    return terminal
}
