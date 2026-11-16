package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The pinned-section divider is a keyed lazy item between production rows. These tests sample its
 * real placed geometry frame by frame while production ChatRows animate around it (#3842).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatListPinnedBoundaryMotionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var dividerColor = Color.Unspecified

    @Test
    fun pinningTravelsTheDividerWithoutAJumpOrPaintingThroughTheCrossingRow() {
        // P1 and P2 pinned; pinning C moves C into the pinned section and the divider down one row.
        assertDividerTravels(
            from = listOf("P1", "P2", "A", "B", "C", "D") to 2,
            to = listOf("C", "P1", "P2", "A", "B", "D") to 3,
            crossingRow = "C",
            settledAbove = "P2",
            settledBelow = "A",
        )
    }

    @Test
    fun unpinningTravelsTheDividerWithoutAJumpOrPaintingThroughTheCrossingRow() {
        assertDividerTravels(
            from = listOf("C", "P1", "P2", "A", "B", "D") to 3,
            to = listOf("P1", "P2", "C", "A", "B", "D") to 2,
            crossingRow = "C",
            settledAbove = "P2",
            settledBelow = "C",
        )
    }

    @Test
    fun promotingAnUnpinnedChatLeavesTheDividerInPlace() {
        // A new message promotes C to the head of the unpinned section; the pinned section is unchanged.
        var state by mutableStateOf(listOf("P1", "P2", "A", "B", "C") to 2)
        mountRows(state.first) { state }
        composeRule.waitForIdle()
        val start = dividerBounds().top
        stepFrames({ state = listOf("P1", "P2", "C", "A", "B") to 2 }) { frame ->
            assertEquals("divider count at frame $frame", 1, dividerCount())
            assertEquals("divider moved at frame $frame", start, dividerBounds().top, 0.5f)
        }
        assertSettledBetween(above = "P2", below = "C")
    }

    @Test
    fun emptyingEitherSectionRemovesTheDividerAtOnceAndRefillingShowsExactlyOne() {
        var state by mutableStateOf(listOf("P1", "A", "B", "C") to 1)
        mountRows(listOf("P1", "A", "B", "C")) { state }
        composeRule.waitForIdle()
        assertEquals(1, dividerCount())

        // Unpin the last pinned row: zero pinned, no divider on any frame.
        stepFrames({ state = listOf("A", "P1", "B", "C") to null }) { frame ->
            assertEquals("stranded divider at frame $frame", 0, dividerCount())
        }
        // Pin one row again: the divider fades in once, between the right rows.
        stepFrames({ state = listOf("B", "A", "P1", "C") to 1 }) { frame ->
            assertEquals("divider count at frame $frame", 1, dividerCount())
        }
        assertSettledBetween(above = "B", below = "A")

        // Pin every row: all pinned, no divider on any frame.
        stepFrames({ state = listOf("A", "B", "P1", "C") to null }) { frame ->
            assertEquals("stranded divider at frame $frame", 0, dividerCount())
        }
    }

    private fun assertDividerTravels(
        from: Pair<List<String>, Int>,
        to: Pair<List<String>, Int>,
        crossingRow: String,
        settledAbove: String,
        settledBelow: String,
    ) {
        var state by mutableStateOf(from)
        mountRows(from.first) { state }
        composeRule.waitForIdle()
        val start = dividerBounds().top
        val rowHeight = rowBounds(from.first.first()).height
        val direction = if (to.second > from.second) 1f else -1f
        var previous = start
        var movingFrames = 0
        var crossingFramesChecked = 0
        stepFrames({ state = to }) { frame ->
            assertEquals("divider count at frame $frame", 1, dividerCount())
            val top = dividerBounds().top
            val step = (top - previous) * direction
            assertTrue("divider reversed at frame $frame ($previous -> $top)", step >= -0.5f)
            assertTrue("divider jumped ${step}px at frame $frame", step < rowHeight / 2f)
            if (step > 0.5f) movingFrames++
            previous = top
            val crossing = rowBounds(crossingRow)
            if (top > crossing.top + 2f && top < crossing.bottom - 2f) {
                assertCoveredByRow(frame)
                crossingFramesChecked++
            }
        }
        assertTrue("divider moved on only $movingFrames frames", movingFrames >= 3)
        assertTrue("the crossing row never covered the divider", crossingFramesChecked > 0)
        assertSettledBetween(settledAbove, settledBelow)
        assertEquals(rowHeight * direction, previous - start, 1f)
    }

    /** The line's own pixels must belong to the opaque row passing over it, not to the divider. */
    private fun assertCoveredByRow(frame: Int) {
        val line = dividerBounds()
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        val y = line.top.toInt()
        val xs = line.left.toInt() until line.right.toInt()
        val linePixels = xs.count { x -> pixels[x, y].closeTo(dividerColor) }
        assertTrue(
            "divider painted through the crossing row at frame $frame ($linePixels of ${xs.count()} px)",
            linePixels < xs.count() / 4,
        )
    }

    private fun assertSettledBetween(
        above: String,
        below: String,
    ) {
        val line = dividerBounds()
        assertTrue("divider above $above", line.top >= rowBounds(above).bottom - 0.5f)
        assertTrue("divider below $below", line.bottom <= rowBounds(below).top + 0.5f)
    }

    private fun stepFrames(
        change: () -> Unit,
        frames: Int = 40,
        check: (Int) -> Unit,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread(change)
        repeat(frames) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            check(frame)
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    private fun mountRows(
        allIds: List<String>,
        state: () -> Pair<List<String>, Int?>,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState = ChatRowPortFixtures.state(context)
        val rows = allIds.associateWith(::realChatListMotionRow)
        composeRule.setContent {
            WhiteNoiseTheme {
                dividerColor = MaterialTheme.colorScheme.outlineVariant
                Surface(Modifier.fillMaxSize()) {
                    val (ids, pinnedCount) = state()
                    ChatListHeadReorderMotionHarness(
                        itemIds = ids,
                        listState = rememberLazyListState(),
                        rowHeight = 48.dp,
                        pinnedCount = pinnedCount,
                        rowContent = { id, modifier, enabled ->
                            Box(modifier) {
                                ChatRow(
                                    item = rows.getValue(id),
                                    appState = appState,
                                    onClick = {},
                                    onOpenProfile = {},
                                    interactionsEnabled = enabled,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    private fun dividerCount(): Int =
        composeRule.onAllNodesWithTag(CHAT_LIST_PINNED_BOUNDARY_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun dividerBounds(): Rect = boundsOf(CHAT_LIST_PINNED_BOUNDARY_TAG)

    private fun rowBounds(id: String): Rect = boundsOf(chatListHeadReorderRowTag(id))

    /** Physical placed bounds, including the in-flight placement offset. */
    private fun boundsOf(tag: String): Rect =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .layoutInfo
            .coordinates
            .boundsInRoot()

    private fun Color.closeTo(other: Color): Boolean =
        abs(red - other.red) < 0.02f && abs(green - other.green) < 0.02f && abs(blue - other.blue) < 0.02f
}
