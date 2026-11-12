package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the live route renderer, not a second animation or screenshot implementation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MeasuredConversationRouteTest {
    @get:Rule val rule = createComposeRule()

    // Keep the interrupted transition and its input/visual assertions in one chronological fixture.
    @Suppress("LongMethod")
    @Test
    fun interruptedEntranceRetainsItsVisibleContainerAndBlocksMisplacedTouches() {
        val route = mutableStateOf<String?>(null)
        var clicks = 0
        var committedRoute: String? = "not committed"
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SideEffect { committedRoute = route.value }
            ConversationRouteAnimatedContent(
                transition = rememberConversationRouteTransition(route.value, suppressMotion = false),
                routeForwardDirection = 1,
                suppressMotion = false,
                contentKey = { it ?: "list" },
                activatingBounds = { if (it != null) Rect(16f, 340f, 344f, 392f) else null },
            ) { destination ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (destination == null) Color.Red else Color.Blue)
                        .clickable { clicks++ },
                )
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle {
            route.value = "chat"
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(64)
        val before = bluePixels()
        assertTrue(
            "Incoming conversation must grow above the live list (blue pixels=$before)",
            before in 1 until 360 * 780,
        )
        rule.onRoot().performTouchInput {
            click(Offset(2f, 2f))
        }
        rule.runOnIdle { assertEquals(0, clicks) }
        // Input injection advances the main clock for its event timestamps, even when
        // autoAdvance is false. Reverse from the actually rendered pre-Back area.
        val immediatelyBeforeBack = bluePixels()
        rule.runOnIdle {
            route.value = null
            Snapshot.sendApplyNotifications()
        }
        // A clock tick delivers animation work before the pending composition commits.
        // Start the reversal assertion at the observed Back commit, not that last entry tick.
        repeat(4) {
            if (committedRoute != null) rule.mainClock.advanceTimeByFrame()
        }
        assertEquals("Back must commit", null, committedRoute)
        var previous = bluePixels()
        assertTrue(
            "Back must retain a partial conversation (before=$immediatelyBeforeBack, after=$previous)",
            previous in 1 until 360 * 780,
        )
        repeat(6) {
            rule.mainClock.advanceTimeByFrame()
            val after = bluePixels()
            assertTrue("Committed Back must never keep growing (before=$previous, after=$after)", after <= previous)
            previous = after
        }
        rule.mainClock.advanceTimeBy(400)
        assertEquals(0, bluePixels())
    }

    private fun bluePixels(): Int {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        var blue = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].blue > 0.95f &&
                    pixels[x, y].red < 0.05f &&
                    pixels[x, y].green < 0.05f
                ) {
                    blue++
                }
            }
        }
        return blue
    }
}
