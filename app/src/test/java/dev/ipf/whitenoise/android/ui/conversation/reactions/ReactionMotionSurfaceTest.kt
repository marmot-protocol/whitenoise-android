package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageReactionSummary
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real row and summary mounts protect layout phase reads and the first-chip host transition. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class ReactionMotionSurfaceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun idleViewportChangesDoNotRecomposeRowsOrMeasureTargets() {
        var viewport by mutableStateOf(Rect(0f, 0f, 360f, 640f))
        val compositions = IntArray(6)
        val owners = List(6) { LocalReactionMotion() }
        rule.setContent {
            WhiteNoiseTheme {
                Column {
                    repeat(6) { index ->
                        ReactionMotionRow(owners[index], viewportBoundsInWindow = { viewport }) {
                            SideEffect { compositions[index]++ }
                            MessageReactionSummary(listOf(ReactionTally("👍", 1, true)), false, onClick = {}, localMotion = owners[index])
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val before = compositions.toList()
        repeat(4) { frame ->
            rule.runOnIdle { viewport = Rect(0f, frame * 10f, 360f, 640f) }
            rule.mainClock.advanceTimeByFrame()
        }
        rule.runOnIdle {
            assertEquals(before, compositions.toList())
            assertTrue(owners.all { it.targets.isEmpty() })
        }
    }

    @Test fun firstRealSummaryGetsATargetDuringItsClippedEntranceAndRespectsClearViewport() {
        var tallies by mutableStateOf(emptyList<ReactionTally>())
        var viewport by mutableStateOf<Rect?>(null)
        val visibility = MutableTransitionState(false)
        lateinit var motion: LocalReactionMotion
        var source = Rect.Zero
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme {
                motion = rememberLocalReactionMotion("message", enabled = true)
                ReactionMotionRow(motion, viewportBoundsInWindow = { viewport }) {
                    Text("👍", Modifier.size(28.dp).reactionSourceBounds { source = it })
                    Spacer(Modifier.height(80.dp))
                    MessageReactionSummary(tallies, false, visibility, onClick = {}, localMotion = motion)
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse(source.isEmpty)
            motion.choose("👍", source)
            tallies = listOf(ReactionTally("👍", 1, true))
            motion.accept("👍", true, motion.takeOrigin("👍"))
        }
        rule.mainClock.advanceTimeBy(48)
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("the actual summary is still entering", visibility.isIdle.not())
            assertTrue("the first chip is measured before its host unclipping", motion.targets["👍"]?.isEmpty == false)
        }
        rule.onNodeWithTag("conversation.reaction.motion.active").assertExists()
        rule.runOnIdle { viewport = Rect.Zero }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("conversation.reaction.motion.active").assertDoesNotExist()
    }
}
