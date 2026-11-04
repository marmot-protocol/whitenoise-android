package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.navigation.FloatingDictationAnchor
import dev.ipf.whitenoise.android.ui.navigation.FloatingDictationLayout
import dev.ipf.whitenoise.android.ui.navigation.LocalFloatingDictationAnchor
import dev.ipf.whitenoise.android.ui.navigation.floatingDictationAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mounts the production shell capsule layout over the production conversation navigation stack, so a
 * stack that stops reading the placed capsule, or a capsule that stops publishing it, fails here (#3584).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationDictationJumpClearanceCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val capsuleVisible = mutableStateOf(true)
    private val capsuleHeight = mutableIntStateOf(48)
    private val capsuleWidth = mutableIntStateOf(168)
    private val inlineControl = mutableStateOf(false)
    private var capsuleTaps = 0
    private var newestTaps = 0
    private var mentionTaps = 0
    private var resumeTaps = 0

    /** Every navigation control clears the floating capsule and receives its own coordinate tap. */
    @Test
    fun jumpControlsSitAboveTheFloatingCapsuleAndReceiveTheirTaps() {
        val anchor = mount()
        assertStackClearsCapsule()
        composeRule.onNodeWithTag(NEWEST).performTouchInput { click() }
        composeRule.onNodeWithTag(MENTION).performTouchInput { click() }
        composeRule.onNodeWithTag(RESUME).performTouchInput { click() }
        composeRule.onNodeWithTag(CAPSULE).performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(1, newestTaps)
            assertEquals(1, mentionTaps)
            assertEquals(1, resumeTaps)
            assertEquals(1, capsuleTaps)
            assertTrue(anchor.floatingCapsuleBounds != null)
        }
    }

    /** Recovery content that grows the capsule lifts the stack again, and dismissal restores the rest position. */
    @Test
    fun capsuleSizeChangesMoveTheStackAndDisposalClearsTheOffset() {
        capsuleVisible.value = false
        val anchor = mount()
        val rest = bounds(NEWEST)
        composeRule.runOnIdle { assertNull(anchor.floatingCapsuleBounds) }

        capsuleVisible.value = true
        assertStackClearsCapsule()
        capsuleHeight.intValue = 132
        capsuleWidth.intValue = 320
        assertStackClearsCapsule()

        capsuleVisible.value = false
        composeRule.runOnIdle { assertNull(anchor.floatingCapsuleBounds) }
        assertEquals(rest, bounds(NEWEST))
    }

    /** A capsule handed back to its origin composer is inline, so the stack keeps its ordinary position. */
    @Test
    fun inlineComposerControlLeavesTheStackAtRest() {
        inlineControl.value = true
        capsuleVisible.value = false
        val anchor = mount()
        val rest = bounds(NEWEST)
        capsuleVisible.value = true
        composeRule.runOnIdle { assertNull(anchor.floatingCapsuleBounds) }
        assertEquals(rest, bounds(NEWEST))
    }

    /** In RTL the stack sits at the physical left, so a narrow right-edge capsule does not move it. */
    @Test
    fun rtlStackBesideTheCapsuleStaysAtRestUntilTheyShareAColumn() {
        capsuleVisible.value = false
        mount(LayoutDirection.Rtl)
        val rest = bounds(NEWEST)
        capsuleVisible.value = true
        composeRule.waitForIdle()
        assertEquals(rest, bounds(NEWEST))
        capsuleWidth.intValue = 340
        assertStackClearsCapsule()
    }

    /** Asserts every visible stack control ends at least the stack gap above the placed capsule. */
    private fun assertStackClearsCapsule() {
        composeRule.waitForIdle()
        val capsule = bounds(CAPSULE)
        listOf(NEWEST, MENTION, RESUME).forEach { tag ->
            val control = bounds(tag)
            assertTrue("$tag $control must end above $capsule", control.bottom + GAP_PX - 0.5f <= capsule.top)
        }
    }

    /** Window bounds of the node tagged [tag]. */
    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow

    /** Mounts a composer obstacle, the production stack and the shell capsule layout in [direction]. */
    private fun mount(direction: LayoutDirection = LayoutDirection.Ltr): FloatingDictationAnchor {
        val anchor = FloatingDictationAnchor()
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalFloatingDictationAnchor provides anchor,
                    LocalLayoutDirection provides direction,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        FloatingDictationLayout(
                            anchor = anchor,
                            content = { ConversationFixture() },
                            control = {
                                if (capsuleVisible.value) {
                                    Box(
                                        Modifier
                                            .size(capsuleWidth.intValue.dp, capsuleHeight.intValue.dp)
                                            .testTag(CAPSULE)
                                            .clickable { capsuleTaps++ },
                                    )
                                }
                            },
                            inlineControl = inlineControl.value,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return anchor
    }

    /** A composer that registers as the bottom obstacle, under the production navigation stack. */
    @Composable
    @Suppress("FunctionNaming")
    private fun ConversationFixture() {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(COMPOSER_DP.dp)
                    .floatingDictationAnchor(),
            )
            ConversationNavigationStack(composerOverlap = COMPOSER_DP.dp) {
                TtsResumeFollowButton(onClick = { resumeTaps++ }, modifier = Modifier.testTag(RESUME))
                Button(onClick = { mentionTaps++ }, modifier = Modifier.testTag(MENTION)) { Text("@ 2") }
                ConversationJumpToNewestButton(
                    unreadIncomingCount = 3,
                    onClick = { newestTaps++ },
                    modifier = Modifier.testTag(NEWEST),
                )
            }
        }
    }

    private companion object {
        const val CAPSULE = "dictation.capsule"
        const val NEWEST = "jump.newest"
        const val MENTION = "jump.mention"
        const val RESUME = "jump.resume"
        const val COMPOSER_DP = 64

        /** mdpi, so the 12dp stack gap is 12px; half a pixel absorbs rounding. */
        const val GAP_PX = 12f
    }
}
