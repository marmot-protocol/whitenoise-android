package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue

internal class ProtectedShellSavedState(
    val holder: SaveableStateHolder,
    val key: String,
)

/** Keeps saved UI state across lock disposal, never across a live account/runtime replacement. */
@Composable
internal fun rememberProtectedShellSavedState(
    accountRef: String?,
    runtimeGeneration: Int,
): ProtectedShellSavedState {
    val holder = rememberSaveableStateHolder()
    // Runtime generations are process-local. Save an epoch instead, so a cold
    // process can recover the pending platform request for the restored account.
    var epoch by rememberSaveable { mutableIntStateOf(0) }
    var previousRuntime by remember { mutableIntStateOf(runtimeGeneration) }
    if (previousRuntime != runtimeGeneration) {
        previousRuntime = runtimeGeneration
        epoch++
    }
    val key = "$epoch:$accountRef"
    DisposableEffect(key) {
        onDispose { holder.removeState(key) }
    }
    return ProtectedShellSavedState(holder, key)
}
