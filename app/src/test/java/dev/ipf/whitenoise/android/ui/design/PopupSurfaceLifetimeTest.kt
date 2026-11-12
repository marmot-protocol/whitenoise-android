package dev.ipf.whitenoise.android.ui.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Terminal actions belong to the presentation that accepted them, never a later account or question. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PopupSurfaceLifetimeTest {
    @get:Rule val rule = createComposeRule()

    /** A paused clock has no platform frame to publish writes made by a test-only callback. */
    private fun publish(block: () -> Unit) =
        rule.runOnIdle {
            block()
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }

    @Test fun firstTerminalActionWinsAndRunsOnceAfterExit() {
        lateinit var surface: PopupSurfaceMotion
        val actions = mutableListOf<String>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                surface = rememberPopupSurfaceMotion(true, { actions += "dismiss" })
            }
        }
        rule.mainClock.advanceTimeBy(400)
        publish {
            surface.close { actions += "confirm" }
            surface.dismiss()
            surface.close { actions += "duplicate" }
            assertFalse(surface.acceptsInput)
            assertEquals(emptyList<String>(), actions)
        }
        rule.mainClock.advanceTimeBy(400)
        publish { assertEquals(listOf("confirm"), actions) }
        rule.mainClock.advanceTimeBy(800)
        publish { assertEquals(listOf("confirm"), actions) }
    }

    @Test fun dismissalCapturesAcceptedCallbackInsteadOfItsReplacement() {
        lateinit var surface: PopupSurfaceMotion
        val callback = mutableStateOf("original")
        val actions = mutableListOf<String>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val accepted = callback.value
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                surface = rememberPopupSurfaceMotion(true, { actions += accepted })
            }
        }
        rule.mainClock.advanceTimeBy(400)
        publish {
            surface.dismiss()
            callback.value = "replacement"
        }
        rule.mainClock.advanceTimeBy(400)
        publish { assertEquals(listOf("original"), actions) }
    }

    @Test fun replacementOwnerCancelsAcceptedAction() {
        lateinit var surface: PopupSurfaceMotion
        val owner = mutableStateOf("first-account")
        var actions = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                surface = rememberPopupSurfaceMotion(true, {}, ownerKey = owner.value)
            }
        }
        rule.mainClock.advanceTimeBy(400)
        publish {
            surface.close { actions++ }
            owner.value = "other-account"
        }
        rule.mainClock.advanceTimeBy(800)
        publish { assertEquals(0, actions) }
    }

    @Test fun externalRemovalCancelsAcceptedAction() {
        lateinit var surface: PopupSurfaceMotion
        val expanded = mutableStateOf(true)
        var actions = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                surface = rememberPopupSurfaceMotion(expanded.value, {})
            }
        }
        rule.mainClock.advanceTimeBy(400)
        publish {
            surface.close { actions++ }
            expanded.value = false
        }
        rule.mainClock.advanceTimeBy(800)
        publish { assertEquals(0, actions) }
    }

    @Test fun busyDismissalIsRejectedBeforeStartingExit() {
        lateinit var surface: PopupSurfaceMotion
        var actions = 0
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides false) {
                surface = rememberPopupSurfaceMotion(true, { actions++ }, dismissEnabled = { false })
            }
        }
        publish { surface.dismiss() }
        publish {
            assertEquals(0, actions)
            assertEquals(true, surface.visible)
        }
    }

    @Test fun forcedSourceNavigationCancelsCompletionAndReducedMotionStillRunsOnce() {
        lateinit var surface: PopupSurfaceMotion
        val animations = mutableStateOf(true)
        var actions = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides animations.value) {
                surface = rememberPopupSurfaceMotion(true, { actions++ })
            }
        }
        rule.mainClock.advanceTimeBy(400)
        publish {
            surface.close { actions++ }
            surface.cancelCompletion()
            animations.value = false
        }
        rule.mainClock.advanceTimeBy(800)
        publish { assertEquals(0, actions) }
    }
}
