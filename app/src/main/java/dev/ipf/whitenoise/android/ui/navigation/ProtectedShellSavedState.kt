package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import java.util.UUID

private val ProtectedShellProcessIdentity = UUID.randomUUID().toString()

internal class ProtectedShellSavedState(
    val holder: SaveableStateHolder,
    val key: String,
)

/** Preserves the live shell on owner changes; retires suspended snapshots when their owner changes. */
@Composable
internal fun rememberProtectedShellSavedState(
    accountRef: String?,
    runtimeGeneration: Int,
    suspended: Boolean,
    processIdentity: String = ProtectedShellProcessIdentity,
): ProtectedShellSavedState {
    val holder = rememberSaveableStateHolder()
    // The process identity distinguishes an activity recreation from a cold
    // process whose runtime counter starts again under the same account.
    var epoch by rememberSaveable { mutableIntStateOf(0) }
    var previousRuntime by rememberSaveable { mutableIntStateOf(runtimeGeneration) }
    var previousProcess by rememberSaveable { mutableStateOf(processIdentity) }
    var previousAccount by rememberSaveable { mutableStateOf(accountRef) }
    var previouslySuspended by rememberSaveable { mutableStateOf(suspended) }
    var composedInThisInstance by remember { mutableStateOf(false) }
    val runtimeChanged = previousProcess == processIdentity && previousRuntime != runtimeGeneration
    if (runtimeChanged || previousAccount != accountRef) {
        // A recreated composition initially holds a snapshot, even when its
        // first frame immediately displays the shell. A running visible shell
        // keeps its provider so account-switch routing and effects survive.
        if (suspended || previouslySuspended || !composedInThisInstance) epoch++
    }
    previousRuntime = runtimeGeneration
    previousProcess = processIdentity
    previousAccount = accountRef
    previouslySuspended = suspended
    SideEffect { composedInThisInstance = true }
    val key = epoch.toString()
    DisposableEffect(key) {
        onDispose { holder.removeState(key) }
    }
    return ProtectedShellSavedState(holder, key)
}
