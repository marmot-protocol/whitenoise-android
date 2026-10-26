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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
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
    @get:Rule val rule = createComposeRule(effectContext = NormalReactionMotionDurationScale)

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
                            MessageReactionSummary(
                                listOf(ReactionTally("👍", 1, true)),
                                false,
                                onClick = {},
                                localMotion = owners[index],
                            )
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
        rule.runOnUiThread {
            assertFalse(source.isEmpty)
            motion.choose("👍", source)
            tallies = listOf(ReactionTally("👍", 1, true))
            motion.accept("👍", true, motion.takeOrigin("👍"))
            Snapshot.sendApplyNotifications()
        }
        rule.waitForIdle()
        assertTargetMeasuredDuringEntrance(visibility, motion)
        rule.onNodeWithTag("conversation.reaction.motion.active").assertExists()
        rule.runOnIdle { viewport = Rect.Zero }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("conversation.reaction.motion.active").assertDoesNotExist()
    }

    private fun assertTargetMeasuredDuringEntrance(
        visibility: MutableTransitionState<Boolean>,
        motion: LocalReactionMotion,
    ) {
        var enteringWithTarget = false
        val samples = mutableListOf<String>()
        // Ten normal frames remain inside the existing 220ms reaction flight.
        for (frame in 0 until 10) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            rule.runOnIdle {
                samples += "frame=$frame current=${visibility.currentState} target=${visibility.targetState} " +
                    "idle=${visibility.isIdle} targetBounds=${motion.targets["👍"]} progress=${motion.progress.value}"
                enteringWithTarget = visibility.targetState &&
                    !visibility.currentState &&
                    !visibility.isIdle &&
                    motion.targets["👍"]?.isEmpty == false
            }
            if (enteringWithTarget) break
        }
        assertTrue("the actual entering summary must measure its first target: $samples", enteringWithTarget)
    }
}

private object NormalReactionMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float = 1f
}
