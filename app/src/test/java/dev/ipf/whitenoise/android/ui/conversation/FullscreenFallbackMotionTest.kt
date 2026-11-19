package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual fullscreen windows without measured origins still retain one complete exit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class FullscreenFallbackMotionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun missingOriginRetainsExitBeforeCompletingOnce() = exercise(animate = true, replace = false)

    @Test fun replacementOwnerCancelsTheAcceptedExit() = exercise(animate = true, replace = true)

    @Test fun reducedMotionCompletesWithoutWaitingForTheExit() = exercise(animate = false, replace = false)

    @Test fun disablingMotionDuringExitCompletesTheAcceptedRequest() {
        val motion = mutableStateOf(true)
        var completions = 0
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides motion.value) {
                    MeasuredPlaybackDialog(origin = null, onDismissRequest = { completions++ }) {
                        TextButton(onClick = dismiss) { Text("Close fullscreen") }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Close fullscreen").performClick()
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle {
            assertEquals(0, completions)
            motion.value = false
        }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertEquals(1, completions) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(1, completions) }
    }

    private fun exercise(
        animate: Boolean,
        replace: Boolean,
    ) {
        val owner = mutableStateOf(0)
        var completions = 0
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides animate) {
                    MeasuredPlaybackDialog(
                        origin = null,
                        ownerKey = owner.value,
                        onDismissRequest = { completions++ },
                    ) {
                        TextButton(onClick = dismiss) { Text("Close fullscreen") }
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Close fullscreen").performClick()
        if (!animate) {
            // Commit the snapped target; there must be no timed exit to wait for.
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle { assertEquals(1, completions) }
            return
        }
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle {
            assertEquals(0, completions)
            if (replace) owner.value++
        }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertEquals(if (replace) 0 else 1, completions) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(if (replace) 0 else 1, completions) }
    }
}
