package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.PushPin
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListPinnedBoundaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun boundaryAppearsOnlyWhenBothPinnedAndUnpinnedRowsAreVisible() {
        assertEquals(2, pinnedBoundaryIndex(listOf(true, true, false, false), showArchived = false))
        assertEquals(1, pinnedBoundaryIndex(listOf(true, false), showArchived = false))
        assertNull(pinnedBoundaryIndex(listOf(false, false), showArchived = false))
        assertNull(pinnedBoundaryIndex(listOf(true, true), showArchived = false))
        assertNull(pinnedBoundaryIndex(listOf(true, false, true), showArchived = false))
        assertNull(pinnedBoundaryIndex(emptyList(), showArchived = false))
        assertNull(pinnedBoundaryIndex(listOf(true, false), showArchived = true))
    }

    @Test
    fun pinnedBoundaryLight() {
        captureRows(
            darkTheme = false,
            amoled = false,
            snapshotName = "chat_list_pinned_boundary_light",
            pinnedTitles = listOf("Pinned project", "Pinned friends"),
            unpinnedTitles = listOf("Recent conversation"),
        )
    }

    @Test
    fun pinnedBoundaryDark() {
        captureRows(
            darkTheme = true,
            amoled = false,
            snapshotName = "chat_list_pinned_boundary_dark",
            pinnedTitles = listOf("Pinned project", "Pinned friends"),
            unpinnedTitles = listOf("Recent conversation"),
        )
    }

    @Test
    fun pinnedBoundaryAmoled() {
        captureRows(
            darkTheme = true,
            amoled = true,
            snapshotName = "chat_list_pinned_boundary_amoled",
            pinnedTitles = listOf("Pinned project", "Pinned friends"),
            unpinnedTitles = listOf("Recent conversation"),
        )
    }

    @Test
    fun unpinnedHeadSettledLight() {
        captureRows(
            darkTheme = false,
            amoled = false,
            snapshotName = "chat_list_unpinned_head_settled_light",
            pinnedTitles = listOf("Pinned friends"),
            unpinnedTitles = listOf("Formerly pinned", "Recent conversation"),
        )
    }

    @Test
    fun pinningMovesTheRetainedBoundaryWithTheRowsBeneathTheCrossingRow() {
        assertBoundaryTravels(
            start = listOf("A", "B", "C", "D", "E", "F"),
            startPinned = 2,
            end = listOf("A", "B", "E", "C", "D", "F"),
            endPinned = 3,
            crossingRow = "E",
        )
    }

    @Test
    fun unpinningMovesTheRetainedBoundaryWithTheRowsInDarkTheme() {
        assertBoundaryTravels(
            start = listOf("A", "B", "C", "D", "E", "F"),
            startPinned = 3,
            end = listOf("A", "C", "D", "B", "E", "F"),
            endPinned = 2,
            crossingRow = "B",
            darkTheme = true,
        )
    }

    @Test
    fun boundaryTravelHoldsInAmoledRtlWithLargeText() {
        assertBoundaryTravels(
            start = listOf("A", "B", "C", "D", "E"),
            startPinned = 2,
            end = listOf("A", "B", "D", "C", "E"),
            endPinned = 3,
            crossingRow = "D",
            darkTheme = true,
            amoled = true,
            rtl = true,
            fontScale = 1.5f,
        )
    }

    @Test
    fun boundaryAppearsOnceAndLeavesWithoutAStrandedLine() {
        val ids = listOf("A", "B", "C", "D")
        var pinned by mutableStateOf(0)
        mountRealRows(ids = { ids }, pinned = { pinned })
        composeRule.waitForIdle()
        assertEquals(0, boundaryCount())

        composeRule.runOnUiThread { pinned = 1 }
        composeRule.waitForIdle()
        assertEquals(1, boundaryCount())
        assertBoundaryBetween("A", "B")

        assertBoundaryGoneOnEveryFrame(movingRow = "B") { pinned = ids.size }

        composeRule.runOnUiThread { pinned = 2 }
        composeRule.waitForIdle()
        assertEquals(1, boundaryCount())
        assertBoundaryBetween("B", "C")

        assertBoundaryGoneOnEveryFrame(movingRow = "C") { pinned = 0 }
    }

    /** Removing the boundary must take it out on the first frame, so no exit fade can strand a line. */
    private fun assertBoundaryGoneOnEveryFrame(
        movingRow: String,
        change: () -> Unit,
    ) {
        val restingTop = rowBounds(movingRow).top
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread(change)
        composeRule.runOnIdle { }
        var rowsMoved = false
        repeat(FRAMES) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            assertEquals("boundary still drawn at frame $frame", 0, boundaryCount())
            rowsMoved = rowsMoved || rowBounds(movingRow).top != restingTop
        }
        assertTrue("$movingRow never moved into the removed boundary's space", rowsMoved)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(0, boundaryCount())
    }

    @Suppress("LongParameterList", "LongMethod")
    private fun assertBoundaryTravels(
        start: List<String>,
        startPinned: Int,
        end: List<String>,
        endPinned: Int,
        crossingRow: String,
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        var ids by mutableStateOf(start)
        var pinned by mutableStateOf(startPinned)
        val lineColor = mountRealRows({ ids }, { pinned }, darkTheme, amoled, rtl, fontScale)
        composeRule.waitForIdle()
        assertBoundaryBetween(start[startPinned - 1], start[startPinned])
        val from = boundary().top
        val (settledLine, settledWidth) = linePixels(boundary(), lineColor())
        assertTrue(
            "settled boundary drew its line on only $settledLine of $settledWidth pixels",
            settledLine > settledWidth / 2,
        )

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            ids = end
            pinned = endPinned
        }
        val samples = mutableListOf(from)
        var crossingChecked = false
        repeat(FRAMES) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            assertEquals("frame $frame", 1, boundaryCount())
            val line = boundary()
            samples += line.top
            val row = rowBounds(crossingRow)
            if (line.top > row.top + CROSSING_INSET && line.top < row.bottom - CROSSING_INSET) {
                assertRowCoversLine(line, lineColor())
                crossingChecked = true
            }
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        composeRule.runOnIdle { }
        val to = boundary().top
        assertBoundaryBetween(end[endPinned - 1], end[endPinned])

        val direction = Math.signum(to - from)
        assertTrue("boundary must change slot", direction != 0f)
        samples.zipWithNext().forEachIndexed { frame, (previous, next) ->
            assertTrue("boundary reversed at frame $frame: $previous -> $next", (next - previous) * direction >= -0.5f)
            assertTrue("boundary overshot at frame $frame: $next", (to - next) * direction >= -0.5f)
            assertTrue(
                "boundary jumped at frame $frame: $previous -> $next of ${to - from}",
                Math.abs(next - previous) < Math.abs(to - from) / 2f,
            )
        }
        assertTrue("the crossing row never passed over the boundary", crossingChecked)
    }

    @Suppress("LongParameterList")
    private fun mountRealRows(
        ids: () -> List<String>,
        pinned: () -> Int,
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ): () -> Int {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState = ChatRowPortFixtures.state(context)
        val rows = listOf("A", "B", "C", "D", "E", "F").associateWith(::realChatListMotionRow)
        var lineColor = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) {
                lineColor = if (amoled) Color(0xFF3D3D3D).toArgb() else MaterialTheme.colorScheme.outlineVariant.toArgb()
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        val currentIds = ids()
                        ChatListHeadReorderMotionHarness(
                            itemIds = currentIds,
                            listState = rememberLazyListState(),
                            rowHeight = 48.dp,
                            pinnedCount =
                                pinnedBoundaryIndex(
                                    pinnedStates = currentIds.indices.map { it < pinned() },
                                    showArchived = false,
                                ),
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
        return { lineColor }
    }

    /** The line pixels inside an overlapping opaque row must belong to the row, not the boundary. */
    private fun assertRowCoversLine(
        line: Rect,
        lineColor: Int,
    ) {
        val (drawn, width) = linePixels(line, lineColor)
        assertTrue(
            "boundary painted through the crossing row on $drawn of $width pixels",
            drawn < width / 2,
        )
    }

    /** Pixels on the line's top row that exactly match [lineColor], and the row's inset width. */
    private fun linePixels(
        line: Rect,
        lineColor: Int,
    ): Pair<Int, Int> {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val y = line.top.toInt()
        val xs = (line.left.toInt() + 1 until line.right.toInt() - 1)
        return xs.count { bitmap.getPixel(it, y) == lineColor } to xs.count()
    }

    private fun assertBoundaryBetween(
        above: String,
        below: String,
    ) {
        val line = boundary()
        assertTrue("boundary must sit below $above", line.top >= rowBounds(above).bottom - 0.5f)
        assertTrue("boundary must sit above $below", line.bottom <= rowBounds(below).top + 0.5f)
    }

    private fun boundaryCount(): Int =
        composeRule
            .onAllNodesWithTag(CHAT_LIST_PINNED_BOUNDARY_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    private fun boundary(): Rect =
        composeRule
            .onNodeWithTag(CHAT_LIST_PINNED_BOUNDARY_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .layoutInfo
            .coordinates
            .boundsInRoot()

    private fun rowBounds(id: String): Rect =
        composeRule
            .onNodeWithTag(chatListHeadReorderRowTag(id), useUnmergedTree = true)
            .fetchSemanticsNode()
            .layoutInfo
            .coordinates
            .boundsInRoot()

    private fun captureRows(
        darkTheme: Boolean,
        amoled: Boolean,
        snapshotName: String,
        pinnedTitles: List<String>,
        unpinnedTitles: List<String>,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        pinnedTitles.forEach { PreviewRow(it, pinned = true) }
                        ChatListPinnedBoundary()
                        unpinnedTitles.forEach { PreviewRow(it, pinned = false) }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(CHAT_LIST_PINNED_BOUNDARY_TAG).assertIsDisplayed()
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/$snapshotName.png")
    }

    private companion object {
        const val SCREENSHOT_TAG = "chat-list-pinned-boundary-preview"
        const val FRAMES = 30
        const val SETTLE_MILLIS = 1_000L
        const val CROSSING_INSET = 12f
    }
}

@Composable
private fun PreviewRow(
    title: String,
    pinned: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (pinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
