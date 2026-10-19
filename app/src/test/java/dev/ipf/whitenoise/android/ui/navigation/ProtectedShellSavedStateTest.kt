package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProtectedShellSavedStateTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun visibleOwnerUpdatesPreserveLiveRoutingAndEffects() {
        val fixture = ShellSnapshotFixture()
        mount(fixture)
        var original: Any? = null
        composeRule.runOnIdle { original = fixture.liveIdentity }
        composeRule.runOnIdle { fixture.account.value = "B" }
        composeRule.runOnIdle { fixture.runtime.value++ }
        composeRule.runOnIdle { fixture.account.value = "A" }
        composeRule.runOnIdle {
            assertSame(original, fixture.liveIdentity)
            assertEquals(0, fixture.disposals)
        }
    }

    @Test fun sameOwnerRestoresSuspendedSnapshot() = exerciseSnapshot(expected = 7) {}

    @Test fun lockedAccountChangeRetiresSnapshot() = exerciseSnapshot { it.account.value = "B" }

    @Test fun lockedRuntimeChangeRetiresSnapshot() = exerciseSnapshot { it.runtime.value++ }

    @Test fun observedLockedAccountRoundTripRetiresSnapshot() =
        exerciseSnapshot { fixture ->
            fixture.account.value = "B"
            composeRule.waitForIdle()
            fixture.account.value = "A"
        }

    @Test fun sameFrameLockAndOwnerChangeRetiresSnapshot() =
        exerciseSnapshot(changeAtLock = true) {
            it.account.value = "B"
        }

    @Test fun sameFrameUnlockAndOwnerChangeRetiresSnapshot() =
        exerciseSnapshot(changeAtUnlock = true) {
            it.account.value = "B"
        }

    @Test fun restoredHiddenSnapshotCannotCrossAnAccountChange() {
        var account = "A"
        val suspended = mutableStateOf(false)
        var visible = 0
        var edit: (() -> Unit)? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val protected = rememberProtectedShellSavedState(account, 1, suspended.value)
            if (!suspended.value) {
                protected.holder.SaveableStateProvider(protected.key) {
                    var saved by rememberSaveable { mutableStateOf(1) }
                    visible = saved
                    edit = { saved = 7 }
                }
            }
        }
        composeRule.runOnIdle { checkNotNull(edit).invoke() }
        composeRule.runOnIdle { suspended.value = true }
        composeRule.waitForIdle()
        // This external account changes only on recreated composition, like a
        // process that restores a snapshot after its authoritative owner changed.
        account = "B"
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { suspended.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, visible) }
    }

    @Test fun restoredVisibleSnapshotCannotCrossAnAccountChange() = exerciseVisibleRestore(changeAccount = true)

    @Test fun recreatedActivityRejectsAReplacementRuntimeInTheSameProcess() {
        exerciseVisibleRestore(changeRuntime = true)
    }

    @Test fun sameAccountVisibleRestoreKeepsItsSnapshot() = exerciseVisibleRestore(expected = 7)

    @Test fun coldProcessRuntimeCounterResetKeepsTheSameAccountSnapshot() {
        exerciseVisibleRestore(changeRuntime = true, coldProcess = true, expected = 7)
    }

    private fun exerciseVisibleRestore(
        changeAccount: Boolean = false,
        changeRuntime: Boolean = false,
        coldProcess: Boolean = false,
        expected: Int = 1,
    ) {
        var account = "A"
        var runtime = 10
        var processIdentity = "process-one"
        var visible = 0
        var edit: (() -> Unit)? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val protected = rememberProtectedShellSavedState(account, runtime, false, processIdentity)
            protected.holder.SaveableStateProvider(protected.key) {
                var saved by rememberSaveable { mutableStateOf(1) }
                visible = saved
                edit = { saved = 7 }
            }
        }
        composeRule.runOnIdle { checkNotNull(edit).invoke() }
        // Plain external values change on recreated composition; the saved
        // bundle still records the owner of the last committed visible frame.
        if (changeAccount) account = "B"
        if (changeRuntime) runtime = if (coldProcess) 0 else runtime + 1
        if (coldProcess) processIdentity = "process-two"
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(expected, visible) }
    }

    private fun exerciseSnapshot(
        expected: Int = 1,
        changeAtLock: Boolean = false,
        changeAtUnlock: Boolean = false,
        change: (ShellSnapshotFixture) -> Unit,
    ) {
        val fixture = ShellSnapshotFixture()
        mount(fixture)
        composeRule.runOnIdle { checkNotNull(fixture.edit).invoke() }
        composeRule.runOnIdle {
            fixture.locked.value = true
            if (changeAtLock) change(fixture)
        }
        composeRule.waitForIdle()
        if (!changeAtLock && !changeAtUnlock) {
            change(fixture)
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            fixture.locked.value = false
            if (changeAtUnlock) change(fixture)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(expected, fixture.observed) }
    }

    private fun mount(fixture: ShellSnapshotFixture) {
        composeRule.setContent {
            val protected =
                rememberProtectedShellSavedState(
                    fixture.account.value,
                    fixture.runtime.value,
                    suspended = fixture.locked.value,
                )
            if (!fixture.locked.value) {
                protected.holder.SaveableStateProvider(protected.key) {
                    val identity = remember { Any() }
                    DisposableEffect(Unit) { onDispose { fixture.disposals++ } }
                    var saved by rememberSaveable { mutableStateOf(1) }
                    fixture.liveIdentity = identity
                    fixture.edit = { saved = 7 }
                    fixture.observed = saved
                }
            }
        }
    }
}

private class ShellSnapshotFixture {
    val locked = mutableStateOf(false)
    val account = mutableStateOf("A")
    val runtime = mutableStateOf(1)
    var liveIdentity: Any? = null
    var edit: (() -> Unit)? = null
    var observed = 0
    var disposals = 0
}
