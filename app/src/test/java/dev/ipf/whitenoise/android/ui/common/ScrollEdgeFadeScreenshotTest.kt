package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.transcriptEdgeFade
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real viewport masks, state changes and gestures for the shared scrolling containers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h480dp-mdpi")
class ScrollEdgeFadeScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var grid: LazyGridState
    private lateinit var column: ScrollState
    private lateinit var scope: CoroutineScope
    private val rows = mutableIntStateOf(40)
    private val fadeEnabled = mutableStateOf(true)
    private val directionReversed = mutableStateOf(false)
    private val replacementList = mutableStateOf<LazyListState?>(null)
    private var background = Color.White

    /** A normal column fades both edges after a real touch scroll. */
    @Test
    fun columnLightTouchScroll() {
        render(Kind.Column)
        rule.onNodeWithTag(VIEWPORT).performTouchInput { swipeUp(startY = 360f, endY = 200f, durationMillis = 1000) }
        rule.waitForIdle()
        assertTrue(column.value > 0)
        capture("column_light_touch", top = true, bottom = true)
    }

    /** Both grid edges work over a dark page without a light-coloured scrim. */
    @Test
    fun gridDarkMiddle() {
        render(Kind.Grid, FixtureOptions(dark = true))
        rule.runOnIdle { scope.launch { grid.scrollToItem(12) } }
        rule.waitForIdle()
        capture("grid_dark_middle", top = true, bottom = true)
    }

    /** Large-font RTL lists keep physical vertical edge direction and AMOLED black behind the mask. */
    @Test
    fun listAmoledRtlLargeFont() {
        render(Kind.List, FixtureOptions(dark = true, amoled = true, rtl = true, fontScale = 2f))
        rule.runOnIdle { scope.launch { list.scrollToItem(10) } }
        rule.waitForIdle()
        capture("list_amoled_rtl_large", top = true, bottom = true)
    }

    /** Logical start of a reversed list is physical bottom, which stays readable. */
    @Test
    fun reversedListStart() {
        render(Kind.List, FixtureOptions(reverse = true))
        capture("list_reverse_start", top = true, bottom = false)
    }

    /** The first resting row is opaque at a normal start; the continuation cue is only below it. */
    @Test
    fun ordinaryListStart() {
        render(Kind.List)
        capture("list_start", top = false, bottom = true)
    }

    /** The final resting row becomes opaque as soon as the end is reached. */
    @Test
    fun ordinaryListEnd() {
        render(Kind.List)
        rule.runOnIdle { scope.launch { list.scrollToItem(39) } }
        rule.waitForIdle()
        capture("list_end", top = true, bottom = false)
    }

    /** A fitting grid has no fade band and needs no offscreen mask. */
    @Test
    fun fittingGrid() {
        rows.intValue = 4
        render(Kind.Grid)
        rule.waitForIdle()
        val node = rule.onNodeWithTag(VIEWPORT)
        node.captureRoboImage("src/test/snapshots/scroll_edges_grid_fitting.png")
        val pixels = node.captureToImage().toPixelMap()
        assertEquals(Color.Cyan, pixels[COLUMN_X, 1])
        assertEquals(Color.Cyan, pixels[COLUMN_X, 79])
        assertFalse(grid.canScrollBackward || grid.canScrollForward)
    }

    /** Removing overflowing rows clears obsolete fades and keeps the surviving content opaque. */
    @Test
    fun dynamicShrinkClearsFade() {
        render(Kind.List)
        rule.runOnIdle { scope.launch { list.scrollToItem(10) } }
        rule.waitForIdle()
        rule.runOnIdle { rows.intValue = 1 }
        rule.waitForIdle()
        assertFalse(list.canScrollBackward || list.canScrollForward)
        val node = rule.onNodeWithTag(VIEWPORT)
        node.captureRoboImage("src/test/snapshots/scroll_edges_list_shrink.png")
        assertEquals(Color.Cyan, node.captureToImage().toPixelMap()[COLUMN_X, 1])
    }

    /** Grid reverse layout uses the same physical-edge policy as a reversed transcript/list. */
    @Test
    fun reversedGridStart() {
        render(Kind.Grid, FixtureOptions(reverse = true))
        capture("grid_reverse_start", top = true, bottom = false)
    }

    /** Reversed eager columns keep their resting bottom edge opaque. */
    @Test
    fun reversedColumnStart() {
        render(Kind.Column, FixtureOptions(reverse = true))
        capture("column_reverse_start", top = true, bottom = false)
    }

    /** Focused editing can suppress masking without replacing scroll state or position. */
    @Test
    fun caretModeKeepsEditorContentOpaque() {
        render(Kind.Column)
        rule.runOnIdle { scope.launch { column.scrollTo(400) } }
        rule.waitForIdle()
        rule.runOnIdle { fadeEnabled.value = false }
        rule.waitForIdle()
        capture("column_active_caret", top = false, bottom = false)
        assertEquals(400, column.value)
    }

    /** Replacing a navigation/profile viewport state cannot retain the previous screen's top fade. */
    @Test
    fun stateReplacementResetsPhysicalEdges() {
        render(Kind.List)
        rule.runOnIdle { scope.launch { list.scrollToItem(10) } }
        rule.waitForIdle()
        rule.runOnIdle { replacementList.value = LazyListState() }
        rule.waitForIdle()
        capture("list_state_replaced", top = false, bottom = true)
        assertEquals(0, list.firstVisibleItemIndex)
    }

    /** A direction change resets edge ownership even when the caller retains its list state. */
    @Test
    fun directionChangeResetsPhysicalEdges() {
        render(Kind.List)
        rule.runOnIdle { directionReversed.value = true }
        rule.waitForIdle()
        capture("list_direction_changed", top = true, bottom = false)
    }

    /** Read-only/no-composer conversations still fade physical continuation edges once. */
    @Test
    fun transcriptWithoutComposerUsesOrdinaryReverseEdges() {
        render(Kind.Transcript)
        capture("transcript_no_composer", top = true, bottom = false)
    }

    /** Empty screens never paint a decorative fade over their background. */
    @Test
    fun emptyColumnHasNoMask() {
        rows.intValue = 0
        render(Kind.Column)
        val node = rule.onNodeWithTag(VIEWPORT)
        node.captureRoboImage("src/test/snapshots/scroll_edges_column_empty.png")
        assertEquals(background, node.captureToImage().toPixelMap()[COLUMN_X, 14])
    }

    /** Very short sheet/editor viewports retain an opaque middle rather than overlapping fades. */
    @Test
    fun smallViewportRetainsItsMiddle() {
        render(Kind.Column, FixtureOptions(heightDp = 80))
        rule.runOnIdle { scope.launch { column.scrollTo(400) } }
        rule.waitForIdle()
        val node = rule.onNodeWithTag(VIEWPORT)
        node.captureRoboImage("src/test/snapshots/scroll_edges_column_tiny.png")
        val pixels = node.captureToImage().toPixelMap()
        assertEquals(Color.Cyan, pixels[COLUMN_X, 40])
        assertTrue(pixels[COLUMN_X, 2] != Color.Cyan)
        assertTrue(pixels[COLUMN_X, 78] != Color.Cyan)
    }

    /** Inspect lateral row shadows with and without an active viewport mask. */
    @Test
    fun elevatedRowsAtViewportSides() {
        val overflow = mutableStateOf(false)
        rule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.size(320.dp, 480.dp).background(Color.White).testTag(VIEWPORT)) {
                    WhiteNoiseLazyColumn(
                        modifier = Modifier.width(240.dp).height(480.dp).align(Alignment.Center),
                    ) {
                        items(if (overflow.value) 40 else 6) { index ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .shadow(12.dp)
                                    .background(Color.Cyan),
                                contentAlignment = Alignment.Center,
                            ) { Text("Elevated row $index") }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(VIEWPORT).captureRoboImage("src/test/snapshots/scroll_edges_side_shadows_fitting.png")
        rule.runOnIdle { overflow.value = true }
        rule.waitForIdle()
        rule.onNodeWithTag(VIEWPORT).captureRoboImage("src/test/snapshots/scroll_edges_side_shadows_overflow.png")
    }

    private fun render(
        kind: Kind,
        options: FixtureOptions = FixtureOptions(),
    ) {
        background = if (options.dark) Color.Black else Color.White
        directionReversed.value = options.reverse
        rule.setContent {
            val initialList = rememberLazyListState()
            list = replacementList.value ?: initialList
            grid = rememberLazyGridState()
            column = rememberScrollState()
            scope = rememberCoroutineScope()
            CompositionLocalProvider(
                LocalLayoutDirection provides if (options.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(1f, options.fontScale),
            ) {
                WhiteNoiseTheme(darkTheme = options.dark, amoled = options.amoled) {
                    ScrollViewport(kind, options.heightDp, list)
                }
            }
        }
        rule.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun ScrollViewport(
        kind: Kind,
        heightDp: Int,
        listState: LazyListState,
    ) {
        Box(Modifier.size(320.dp, heightDp.dp).background(background).testTag(VIEWPORT)) {
            when (kind) {
                Kind.Column -> ColumnViewport()
                Kind.List -> ListViewport(listState)
                Kind.Transcript -> TranscriptViewport(listState)
                Kind.Grid -> GridViewport()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ColumnViewport() {
        val scrolling =
            if (fadeEnabled.value) {
                Modifier.fadingVerticalScroll(column, reverseScrolling = directionReversed.value)
            } else {
                Modifier
                    .scrollEdgeFade(column, reverseScrolling = directionReversed.value, fadeEnabled = false)
                    .verticalScroll(column, reverseScrolling = directionReversed.value)
            }
        Column(Modifier.fillMaxSize().then(scrolling)) {
            repeat(rows.intValue) { RowFixture(it) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ListViewport(listState: LazyListState) {
        WhiteNoiseLazyColumn(
            state = listState,
            reverseLayout = directionReversed.value,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(rows.intValue) { RowFixture(it) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun TranscriptViewport(listState: LazyListState) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().transcriptEdgeFade(listState, composerOverlap = 0.dp),
        ) {
            items(rows.intValue) { RowFixture(it) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun GridViewport() {
        WhiteNoiseLazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = grid,
            reverseLayout = directionReversed.value,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(rows.intValue) { RowFixture(it) }
        }
    }

    private data class FixtureOptions(
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val rtl: Boolean = false,
        val fontScale: Float = 1f,
        val reverse: Boolean = false,
        val heightDp: Int = 480,
    )

    @androidx.compose.runtime.Composable
    private fun RowFixture(index: Int) {
        Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Cyan), contentAlignment = Alignment.CenterStart) {
            Text("Row $index")
        }
    }

    private fun capture(
        name: String,
        top: Boolean,
        bottom: Boolean,
    ) {
        val node = rule.onNodeWithTag(VIEWPORT)
        node.captureRoboImage("src/test/snapshots/scroll_edges_$name.png")
        val pixels = node.captureToImage().toPixelMap()
        assertEquals(Color.Cyan, pixels[COLUMN_X, 240])
        for ((edge, faded) in listOf(14 to top, 466 to bottom)) {
            if (faded) {
                assertTrue("$name edge $edge should blend with background", pixels[COLUMN_X, edge] != Color.Cyan)
                assertTrue("$name edge $edge should retain content", pixels[COLUMN_X, edge] != background)
            } else {
                assertEquals("$name edge $edge should be fully readable", Color.Cyan, pixels[COLUMN_X, edge])
            }
        }
    }

    private enum class Kind { Column, List, Grid, Transcript }

    private companion object {
        const val VIEWPORT = "scroll-edge-viewport"
        const val COLUMN_X = 160
    }
}
