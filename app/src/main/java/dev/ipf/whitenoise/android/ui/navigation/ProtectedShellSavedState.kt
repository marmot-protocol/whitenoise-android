package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
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

/**
 * Preserves the live shell on owner changes; retires suspended snapshots when their owner changes.
 * Lock suspension and Activity recreation keep the snapshot, so external results land in it later.
 */
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
    val key = epoch.toString()
    var retainedKey by rememberSaveable { mutableStateOf<String?>(null) }
    SideEffect {
        composedInThisInstance = true
        // Retire a snapshot only when its owner is replaced. Composition teardown (Activity
        // recreation) must not remove it — the saved-instance bundle may still reference the
        // holder's live state map, so a removal there would erase the state being restored.
        retainedKey?.takeIf { it != key }?.let(holder::removeState)
        retainedKey = key
    }
    return ProtectedShellSavedState(holder, key)
}
