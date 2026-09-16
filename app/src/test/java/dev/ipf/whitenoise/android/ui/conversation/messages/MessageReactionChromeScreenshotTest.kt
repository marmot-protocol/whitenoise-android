@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val ROOT_TAG = "reaction.adjacency.root"
private const val REACTED_TAG = "reaction.adjacency.reacted"
private const val NEXT_TAG = "reaction.adjacency.next"
private const val PLAIN_TAG = "reaction.adjacency.plain"
private const val BUBBLE_HEIGHT_DP = 48f
private const val LIST_GAP_DP = 8f

// The reaction row draws 23dp pills inside a 48dp touch row: a reacted row may add the pills to the
// rhythm, never the touch row's empty half.
private const val REACTION_PILL_HEIGHT_DP = 23f
private const val MEASUREMENT_TOLERANCE_DP = 2f

// Just inside the next bubble's top edge, where the reaction row's touch slop overhangs it.
private const val TOP_EDGE_PROBE_PX = 2f

/**
 * The vertical rhythm around a reacted bubble. The chips are allowed to take the space they draw and
 * no more, so a reaction never pushes the next message — or the composer — away from the bubble.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MessageReactionChromeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A reacted row adds at most the pills it draws to the gap before the next message. */
    @Test
    fun reactedRowAddsOnlyThePillProtrusion() {
        renderAdjacency(mine = false)
        val reacted = bounds(REACTED_TAG)
        val next = bounds(NEXT_TAG)
        val plain = bounds(PLAIN_TAG)
        val reactedGap = next.top - reacted.bottom
        val plainGap = plain.top - next.bottom
        assertEquals("the control gap is the plain list spacing", LIST_GAP_DP, plainGap, MEASUREMENT_TOLERANCE_DP)
        assertTrue(
            "a reacted row added ${reactedGap - plainGap}dp, more than the ${REACTION_PILL_HEIGHT_DP}dp it draws",
            reactedGap - plainGap <= REACTION_PILL_HEIGHT_DP + MEASUREMENT_TOLERANCE_DP,
        )
    }

    /** The same bound holds for an outgoing bubble, whose chips sit on the other edge. */
    @Test
    fun outgoingReactedRowAddsOnlyThePillProtrusion() {
        renderAdjacency(mine = true)
        val reacted = bounds(REACTED_TAG)
        val next = bounds(NEXT_TAG)
        val plain = bounds(PLAIN_TAG)
        assertTrue(
            "an outgoing reacted row added too much vertical space",
            (next.top - reacted.bottom) - (plain.top - next.bottom) <=
                REACTION_PILL_HEIGHT_DP + MEASUREMENT_TOLERANCE_DP,
        )
    }

    /** The last reacted row ends with its chips, so the composer keeps its ordinary inset. */
    @Test
    fun lastReactedRowKeepsTheComposerInset() {
        renderLastRow()
        val root = bounds(ROOT_TAG)
        val reacted = bounds(REACTED_TAG)
        assertTrue(
            "the transcript extends ${root.bottom - reacted.bottom}dp past the last reacted bubble",
            root.bottom - reacted.bottom <= REACTION_PILL_HEIGHT_DP + MEASUREMENT_TOLERANCE_DP,
        )
    }

    /**
     * The touch row may overflow its reported height, but the next bubble still owns its own top edge.
     * The tap is dispatched as real pointer input at that edge rather than through the node's click
     * action: only a routed press proves the overlapping reaction row does not consume it.
     */
    @Test
    fun nextBubbleTopEdgeStillReceivesTaps() {
        var nextClicks = 0
        renderAdjacency(mine = false, onNextClick = { nextClicks += 1 })
        val next = bounds(NEXT_TAG)
        composeRule.onRoot().performTouchInput {
            click(Offset(next.center.x, next.top + TOP_EDGE_PROBE_PX))
        }
        composeRule.runOnIdle { assertEquals(1, nextClicks) }
    }

    /** Light theme baseline of a reacted row between two plain rows. */
    @Test
    fun reactionAdjacencyLight() {
        renderAdjacency(mine = false)
        capture("message_reactions_adjacency_light")
    }

    /** Dark theme baseline. */
    @Test
    fun reactionAdjacencyDark() {
        renderAdjacency(mine = false, darkTheme = true)
        capture("message_reactions_adjacency_dark")
    }

    /** AMOLED baseline, where the chips sit on pure black. */
    @Test
    fun reactionAdjacencyAmoled() {
        renderAdjacency(mine = false, darkTheme = true, amoled = true)
        capture("message_reactions_adjacency_amoled")
    }

    /** Large text keeps the same rhythm bound. */
    @Test
    fun reactionAdjacencyLargeFont() {
        renderAdjacency(mine = false, fontScale = 1.3f)
        capture("message_reactions_adjacency_large_font")
    }

    /** Root-space bounds of a tagged row, for the spacing assertions. */
    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** Captures the mounted transcript as the named baseline. */
    private fun capture(name: String) {
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Mounts a reacted bubble between two plain ones, in the transcript's own list spacing. */
    private fun renderAdjacency(
        mine: Boolean,
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        onNextClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) {
                TranscriptColumn {
                    BubbleStandIn(REACTED_TAG)
                    MessageReactionSummary(
                        tallies = listOf(ReactionTally("👍", 1, mine = false)),
                        mine = mine,
                        onClick = {},
                    )
                    BubbleStandIn(NEXT_TAG, onClick = onNextClick)
                    BubbleStandIn(PLAIN_TAG)
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Mounts a reacted bubble as the transcript's last row, the case that meets the composer. */
    private fun renderLastRow() {
        composeRule.setContent {
            WhiteNoiseTheme {
                TranscriptColumn {
                    BubbleStandIn(REACTED_TAG)
                    MessageReactionSummary(
                        tallies = listOf(ReactionTally("👍", 1, mine = false)),
                        mine = false,
                        onClick = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** The transcript's own container: fixed width and the list spacing rows are laid out with. */
    @Composable
    private fun TranscriptColumn(content: @Composable ColumnScope.() -> Unit) {
        Surface(modifier = Modifier.width(320.dp).testTag(ROOT_TAG)) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(LIST_GAP_DP.dp),
            ) {
                content()
            }
        }
    }

    /** A bubble-sized block standing in for a rendered message row. */
    @Composable
    private fun ColumnScope.BubbleStandIn(
        tag: String,
        onClick: (() -> Unit)? = null,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(BUBBLE_HEIGHT_DP.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .testTag(tag),
        )
    }
}
