package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real drawing/input wrapper rather than a duplicate of its geometry. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ProfileGroupOpeningPageUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun expandingPageCoversWindowAndBlocksTouchesUntilSettled() {
        composeRule.mainClock.autoAdvance = false
        var clicks = 0
        var background = Color.Unspecified
        composeRule.setContent {
            WhiteNoiseTheme {
                background = MaterialTheme.colorScheme.background
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                        ProfileGroupOpeningPage(Rect(40f, 100f, 300f, 160f), "owner") {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Red)
                                    .clickable { clicks++ }
                                    .testTag("destination"),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // Outside the launch control the disposed route/window must never show through.
        assertEquals(background, composeRule.onRoot().captureToImage().toPixelMap()[2, 2])
        composeRule.onRoot().performTouchInput { click(center) }
        composeRule.runOnIdle { assertEquals(0, clicks) }
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNodeWithTag("destination").assertExists().performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun reducedMotionDoesNotDelayDestinationInput() {
        var clicks = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides false) {
                    ProfileGroupOpeningPage(Rect(40f, 100f, 300f, 160f), "owner") {
                        Box(Modifier.fillMaxSize().clickable { clicks++ }.testTag("destination"))
                    }
                }
            }
        }
        composeRule.onNodeWithTag("destination").performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun offscreenOriginSettlesWithoutInputDelay() {
        var clicks = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    ProfileGroupOpeningPage(Rect(-100f, -100f, -50f, -50f), "owner") {
                        Box(Modifier.fillMaxSize().clickable { clicks++ }.testTag("destination"))
                    }
                }
            }
        }
        // Layout retires an invalid origin; commit that measured state to semantics
        // without advancing through the entrance animation.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("destination").performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }
}
