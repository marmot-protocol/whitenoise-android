package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Motion regression for issue #2145: reaction host height and chip content must animate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageReactionSummaryAnimationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstReactionHostHeightAnimatesOverFrames() {
        var tallies by mutableStateOf<List<ReactionTally>>(emptyList())

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ReactionHostHarness(tallies = tallies)
        }
        composeRule.waitForIdle()

        val heightBefore = columnHeight()
        composeRule.runOnUiThread {
            tallies = listOf(ReactionTally(emoji = "👍", count = 1, mine = true))
        }
        composeRule.runOnIdle { }

        val observedHeights = advanceFramesAndCollectHeights(frameCount = ANIMATION_SAMPLE_FRAMES)
        composeRule.mainClock.advanceTimeBy(DEADLINE_REMAINDER_MILLIS)
        composeRule.runOnIdle { }
        val heightAtDeadline = columnHeight()
        composeRule.mainClock.advanceTimeBy(SETTLED_PROBE_MILLIS)
        composeRule.runOnIdle { }

        val heightAfter = columnHeight()
        assertEquals(heightAtDeadline, heightAfter, HEIGHT_TOLERANCE)
        assertTrue("reaction host should grow after the first reaction", heightAfter > heightBefore + HEIGHT_TOLERANCE)
        assertTrue(
            "reaction host height should pass through an intermediate value " +
                "while entering: $observedHeights settled=$heightAfter",
            observedHeights.any { height ->
                height > heightBefore + HEIGHT_TOLERANCE && height < heightAfter - HEIGHT_TOLERANCE
            },
        )
    }

    @Test
    fun lastReactionHostHeightAnimatesOutOverFrames() {
        var tallies by mutableStateOf(listOf(ReactionTally(emoji = "👍", count = 1, mine = true)))

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ReactionHostHarness(tallies = tallies)
        }
        composeRule.waitForIdle()

        val heightBefore = columnHeight()
        composeRule.runOnUiThread { tallies = emptyList() }
        composeRule.runOnIdle { }

        val observedHeights = advanceFramesAndCollectHeights(frameCount = ANIMATION_SAMPLE_FRAMES)
        composeRule.mainClock.advanceTimeBy(DEADLINE_REMAINDER_MILLIS)
        composeRule.runOnIdle { }
        val heightAtDeadline = columnHeight()
        composeRule.mainClock.advanceTimeBy(SETTLED_PROBE_MILLIS)
        composeRule.runOnIdle { }

        val heightAfter = columnHeight()
        assertEquals(heightAtDeadline, heightAfter, HEIGHT_TOLERANCE)
        assertTrue(
            "reaction host should shrink after removing the last reaction",
            heightAfter < heightBefore - HEIGHT_TOLERANCE,
        )
        assertTrue(
            "reaction host height should pass through an intermediate value " +
                "while exiting: $observedHeights settled=$heightAfter",
            observedHeights.any { height ->
                height < heightBefore - HEIGHT_TOLERANCE && height > heightAfter + HEIGHT_TOLERANCE
            },
        )
    }

    @Test
    fun reactionChipContentUpdateDoesNotChangeHostHeight() {
        var tallies by mutableStateOf(listOf(ReactionTally(emoji = "👍", count = 1, mine = true)))

        composeRule.setContent {
            ReactionHostHarness(tallies = tallies)
        }
        composeRule.waitForIdle()

        val heightBefore = columnHeight()
        composeRule.runOnUiThread {
            tallies =
                listOf(
                    ReactionTally(emoji = "👍", count = 2, mine = true),
                    ReactionTally(emoji = "❤️", count = 1, mine = false),
                )
        }
        composeRule.waitForIdle()

        assertEquals(heightBefore, columnHeight(), HEIGHT_TOLERANCE)
        composeRule.onNodeWithText("👍❤️", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("3", useUnmergedTree = true).assertExists()
    }

    @Test
    fun midListNeighborMovesThroughIntermediateFrames() {
        var tallies by mutableStateOf<List<ReactionTally>>(emptyList())

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ReactionListHarness(tallies = tallies)
        }
        composeRule.waitForIdle()

        val bubbleTopBefore = nodeTop(BUBBLE_TEXT_TAG)
        val neighborTopBefore = nodeTop(FOLLOWING_MESSAGE_TAG)
        composeRule.runOnUiThread {
            tallies = listOf(ReactionTally(emoji = "👍", count = 1, mine = true))
        }
        composeRule.runOnIdle { }

        val observedNeighborTops =
            buildList {
                repeat(ANIMATION_SAMPLE_FRAMES) {
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.runOnIdle { }
                    assertEquals(bubbleTopBefore, nodeTop(BUBBLE_TEXT_TAG), POSITION_TOLERANCE)
                    add(nodeTop(FOLLOWING_MESSAGE_TAG))
                }
            }
        composeRule.mainClock.advanceTimeBy(SETTLED_PROBE_MILLIS)
        composeRule.runOnIdle { }
        val neighborTopAfter = nodeTop(FOLLOWING_MESSAGE_TAG)

        assertTrue(neighborTopAfter > neighborTopBefore + POSITION_TOLERANCE)
        assertTrue(
            "the following row should move through an intermediate position: " +
                "$observedNeighborTops settled=$neighborTopAfter",
            observedNeighborTops.any { top ->
                top > neighborTopBefore + POSITION_TOLERANCE && top < neighborTopAfter - POSITION_TOLERANCE
            },
        )
    }

    private fun advanceFramesAndCollectHeights(frameCount: Int): List<Float> {
        val heights = mutableListOf<Float>()
        repeat(frameCount) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            heights += columnHeight()
        }
        return heights
    }

    private fun columnHeight(): Float =
        composeRule
            .onNodeWithTag(COLUMN_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
            .height

    private fun nodeTop(tag: String): Float =
        composeRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .boundsInRoot
            .top
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageReactionSummaryReducedMotionTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = ReducedMotionDurationScale)

    @Test
    fun reactionHostUpdatesImmediatelyWhenMotionIsDisabled() {
        var tallies by mutableStateOf<List<ReactionTally>>(emptyList())

        composeRule.setContent {
            ReactionHostHarness(tallies = tallies)
        }
        composeRule.waitForIdle()
        val heightWithoutReaction = columnHeight()

        composeRule.runOnUiThread {
            tallies = listOf(ReactionTally(emoji = "👍", count = 1, mine = true))
        }
        composeRule.waitForIdle()
        assertTrue(columnHeight() > heightWithoutReaction + HEIGHT_TOLERANCE)
        composeRule.onNodeWithText("👍", useUnmergedTree = true).assertExists()

        composeRule.runOnUiThread { tallies = emptyList() }
        composeRule.waitForIdle()
        assertEquals(heightWithoutReaction, columnHeight(), HEIGHT_TOLERANCE)
        composeRule.onNodeWithText("👍", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun columnHeight(): Float =
        composeRule
            .onNodeWithTag(COLUMN_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
            .height
}

private object ReducedMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float = 0f
}

@Composable
private fun ReactionHostHarness(tallies: List<ReactionTally>) {
    val visibilityState = remember { MutableTransitionState(tallies.isNotEmpty()) }
    visibilityState.targetState = tallies.isNotEmpty()
    WhiteNoiseTheme(darkTheme = true, amoled = true) {
        Surface(modifier = Modifier.widthIn(max = 220.dp)) {
            Column(modifier = Modifier.testTag(COLUMN_TAG)) {
                MessageBubbleFrame(
                    presentation = messageBubblePresentation(deleted = false, mine = false),
                    highlighted = false,
                    mine = false,
                    mentionedSelf = false,
                    mentionedYouLabel = "Mentioned you",
                ) {
                    Text(
                        text = "Can you review the file?",
                        modifier = Modifier.testTag(BUBBLE_TEXT_TAG),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                MessageReactionSummary(
                    tallies = tallies,
                    mine = false,
                    visibilityState = visibilityState,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun ReactionListHarness(tallies: List<ReactionTally>) {
    WhiteNoiseTheme(darkTheme = true, amoled = true) {
        Column {
            Text("Message before")
            ReactionHostHarness(tallies = tallies)
            Text("Message after", modifier = Modifier.testTag(FOLLOWING_MESSAGE_TAG))
        }
    }
}

private const val COLUMN_TAG = "message-reaction-host-column"
private const val BUBBLE_TEXT_TAG = "message-reaction-bubble-text"
private const val FOLLOWING_MESSAGE_TAG = "message-reaction-following-message"
private const val HEIGHT_TOLERANCE = 0.5f
private const val POSITION_TOLERANCE = 0.5f
private const val ANIMATION_SAMPLE_FRAMES = 8
private const val DEADLINE_REMAINDER_MILLIS = 122L
private const val SETTLED_PROBE_MILLIS = 100L
