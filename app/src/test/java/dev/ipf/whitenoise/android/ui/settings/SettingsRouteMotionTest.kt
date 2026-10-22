package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsRouteMotionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun nestedDepthAndRtlUseTheExistingBackHierarchy() {
        assertEquals(0, settingsDetailDepth(null))
        assertEquals(1, settingsDetailDepth(SettingsDetail.Appearance))
        assertEquals(2, settingsDetailDepth(SettingsDetail.ChatBubbleColors))
        assertEquals(1, settingsRouteDirection(0, 1, LayoutDirection.Ltr))
        assertEquals(-1, settingsRouteDirection(2, 1, LayoutDirection.Ltr))
        assertEquals(-1, settingsRouteDirection(0, 1, LayoutDirection.Rtl))
        assertEquals(1, settingsRouteDirection(2, 1, LayoutDirection.Rtl))
    }

    @Test fun rapidBackDisposesOutgoingDestinationImmediatelyAndLockRemovesContent() {
        val destination = mutableStateOf(0)
        val unlocked = mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SettingsRouteMotion(
                target = destination.value,
                depth = destination.value,
                ownerKey = "account",
                contentVisible = unlocked.value,
                animationsEnabled = true,
            ) { Text("Destination $it", Modifier.testTag("destination.$it")) }
        }
        rule.runOnIdle { destination.value = 1 }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("destination.0").assertDoesNotExist()
        rule.onNodeWithTag("destination.1").assertExists()
        rule.runOnIdle { destination.value = 0 }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("destination.1").assertDoesNotExist()
        rule.onNodeWithTag("destination.0").assertExists()
        rule.runOnIdle { unlocked.value = false }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("destination.0").assertDoesNotExist()
    }

    @Test fun destinationMovesThenSettlesAndLiveMotionDisableSnaps() {
        val destination = mutableStateOf(0)
        val motion = mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SettingsRouteMotion(destination.value, destination.value, "account", animationsEnabled = motion.value) {
                Text("Destination $it", Modifier.testTag("moving.destination"))
            }
        }
        rule.runOnIdle { destination.value = 1 }
        rule.mainClock.advanceTimeByFrame()
        val first =
            rule
                .onNodeWithTag("moving.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left
        assertTrue("New destination should travel from the logical forward edge", first > 0f)
        rule.mainClock.advanceTimeBy(96)
        val middle =
            rule
                .onNodeWithTag("moving.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left
        assertTrue("Midpoint should approach its final position", middle >= 0f && middle < first)
        rule.runOnIdle { motion.value = false }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(
            0f,
            rule
                .onNodeWithTag("moving.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left,
            0.1f,
        )
        rule.mainClock.advanceTimeBy(300)
        assertEquals(
            0f,
            rule
                .onNodeWithTag("moving.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left,
            0.1f,
        )
    }

    @Test fun liveRtlChangeMirrorsTheSameBackTransition() {
        val destination = mutableStateOf(2)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                SettingsRouteMotion(destination.value, destination.value, "account", animationsEnabled = true) {
                    Text("Destination $it", Modifier.fillMaxWidth().testTag("back.destination"))
                }
            }
        }
        rule.runOnIdle { destination.value = 1 }
        rule.mainClock.advanceTimeBy(32)
        assertTrue(
            rule
                .onNodeWithTag("back.destination")
                .getUnclippedBoundsInRoot()
                .left.value < 0f,
        )
        rule.runOnIdle { direction.value = LayoutDirection.Rtl }
        rule.mainClock.advanceTimeByFrame()
        assertTrue(
            rule
                .onNodeWithTag("back.destination")
                .getUnclippedBoundsInRoot()
                .left.value > 0f,
        )
        rule.mainClock.advanceTimeBy(300)
        assertEquals(
            0f,
            rule
                .onNodeWithTag("back.destination")
                .getUnclippedBoundsInRoot()
                .left.value,
            0.1f,
        )
    }

    @Test fun disabledMotionAndAccountReplacementHaveOneDestination() {
        val owner = mutableStateOf("first")
        val destination = mutableStateOf(0)
        rule.setContent {
            SettingsRouteMotion(destination.value, destination.value, owner.value, animationsEnabled = false) {
                Text(owner.value, Modifier.testTag("${owner.value}.$it"))
            }
        }
        rule.runOnIdle { destination.value = 1 }
        rule.onNodeWithTag("first.0").assertDoesNotExist()
        rule.onNodeWithTag("first.1").assertExists()
        rule.runOnIdle { owner.value = "second" }
        rule.onNodeWithTag("first.1").assertDoesNotExist()
        rule.onNodeWithTag("second.1").assertExists()
    }
}
