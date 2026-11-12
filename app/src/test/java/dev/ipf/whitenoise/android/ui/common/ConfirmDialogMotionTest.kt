package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Exercise the real shared confirmation entry point, not only its visibility helper. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConfirmDialogMotionTest {
    @get:Rule val composeRule = createComposeRule()
    private val rule get() = composeRule

    @Test fun terminalActionWaitsForExitAndRunsOnlyOnce() {
        val open = mutableStateOf(true)
        var confirmed = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    if (open.value) {
                        ConfirmDialog("Remove", "Remove this item?", "Remove", {
                            confirmed++
                            open.value = false
                        }, { open.value = false })
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("confirmation.confirm").performClick()
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle { assertEquals(0, confirmed) }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertEquals(1, confirmed) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun replacementPresentationCancelsAcceptedTerminalAction() {
        val owner = mutableStateOf<Any>(Any())
        val accepted = mutableListOf<Any>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val request = owner.value
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    ConfirmDialog("Remove", "Remove this item?", "Remove", {
                        accepted += request
                    }, {}, ownerKey = request)
                }
            }
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("confirmation.confirm").performClick()
        rule.runOnIdle { owner.value = Any() }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(emptyList<Any>(), accepted) }
        rule.onNodeWithTag("confirmation.confirm").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertEquals(listOf(owner.value), accepted) }
    }

    @Test fun reducedMotionConfirmsWithoutAVisibilityDelay() {
        var confirmed = 0
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides false) {
                    ConfirmDialog("Remove", "Remove this item?", "Remove", { confirmed++ }, {})
                }
            }
        }
        rule.onNodeWithTag("confirmation.confirm").performClick()
        rule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun captureConfirmationFrames() {
        org.junit.Assume.assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val shown = mutableStateOf(false)
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    if (shown.value) {
                        ConfirmDialog(
                            "Remove",
                            "Remove this item?",
                            "Remove",
                            { shown.value = false },
                            { shown.value = false },
                        )
                    }
                }
            }
        }
        captureTransitionFrames("confirmation/forward") { rule.runOnIdle { shown.value = true } }
        captureTransitionFrames("confirmation/reverse") { rule.onNodeWithTag("confirmation.confirm").performClick() }
    }

    private fun captureTransitionFrames(
        name: String,
        action: () -> Unit,
    ) {
        check(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val folder = File("build/animation-frames/$name").apply { mkdirs() }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        composeRule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(32) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }
}
