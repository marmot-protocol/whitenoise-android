package dev.ipf.whitenoise.android.ui.design

import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AnimatedDropdownMenuBackTest {
    @get:Rule val rule = createComposeRule()

    // One chronological scenario verifies Back registration survives exit and unregisters exactly once.
    @Suppress("LongMethod")
    @Test
    fun overlayBackRemainsRegisteredThroughExitAndDismissesOnce() {
        val expanded = mutableStateOf(false)
        var callback: OnBackInvokedCallback? = null
        var registrations = 0
        var unregistrations = 0
        var dismissals = 0
        var mounted = false
        val register: KeyboardSafePopupBackRegistration = { priority, registered ->
            assertEquals(OnBackInvokedDispatcher.PRIORITY_OVERLAY, priority)
            registrations++
            callback = registered
            val unregister: () -> Unit = {
                unregistrations++
                callback = null
            }
            unregister
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                AnimatedDropdownMenu(
                    expanded = expanded.value,
                    onDismissRequest = {
                        dismissals++
                        expanded.value = false
                    },
                    keyboardPreserving = true,
                    backRegistration = register,
                ) {
                    DisposableEffect(Unit) {
                        mounted = true
                        onDispose { mounted = false }
                    }
                    Box(Modifier.size(120.dp))
                }
            }
        }
        rule.runOnIdle { assertEquals(0, registrations) }
        rule.runOnIdle {
            expanded.value = true
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle {
            assertEquals(1, registrations)
            assertNotNull(callback)
            callback!!.onBackInvoked()
        }
        rule.mainClock.advanceTimeBy(96)
        rule.runOnIdle {
            assertEquals(true, mounted)
            assertEquals(0, dismissals)
            assertEquals(0, unregistrations)
            assertNotNull(callback)
            callback!!.onBackInvoked()
            callback!!.onBackInvoked()
        }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle {
            assertEquals(1, registrations)
            assertEquals(1, unregistrations)
            assertEquals(1, dismissals)
            assertEquals(false, mounted)
            assertNull(callback)
        }
    }
}
