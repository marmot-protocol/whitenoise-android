package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the edge fade actually paints. The arithmetic is covered separately; this pins that the mask
 * reaches the screen at all, and that an edge with nothing beyond it is left alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h480dp-mdpi")
class TranscriptEdgeFadeRenderTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope

    /**
     * Parked mid-history the transcript dissolves into the bar above, reaches the composer at full
     * strength, and thins away behind it to nothing by the bottom of the screen.
     */
    @Test
    fun aTranscriptParkedMidHistoryDissolvesAtBothEdges() {
        render()
        rule.runOnIdle { scope.launch { list.scrollToItem(MIDDLE_ROW) } }
        rule.waitForIdle()
        val pixels = rule.onNodeWithTag(FRAME).captureToImage().toPixelMap()
        // The pixel assertions below state the intent; the baseline catches everything else the
        // mask could change about how the transcript meets its chrome.
        rule.onNodeWithTag(FRAME).captureRoboImage(SNAPSHOT)

        assertEquals("rows away from either edge stay untouched", ROW, pixels[COLUMN, 240])
        assertEquals(
            "a row must reach the composer at full strength rather than dimming in front of it",
            ROW,
            pixels[COLUMN, COMPOSER_TOP_EDGE - 2],
        )
        assertTrue(
            "the row must be half gone across the middle of the band below the bar",
            pixels[COLUMN, TOP_BAND_MIDPOINT].isPartlyFaded(),
        )
        assertTrue(
            "the row must be half gone halfway down behind the composer",
            pixels[COLUMN, BEHIND_COMPOSER_MIDPOINT].isPartlyFaded(),
        )
        assertTrue(
            "the row must be all but gone by the bottom of the screen",
            pixels[COLUMN, 478].red > 0.9f,
        )
    }

    /**
     * The oldest message rests a few pixels under the bar. Fading an edge with nothing beyond it
     * would hold that message permanently dimmed, which reads as a rendering fault.
     */
    @Test
    fun theOldestMessageIsNotDimmedOnceHistoryRunsOut() {
        render()
        rule.runOnIdle { scope.launch { list.scrollToItem(ROW_COUNT - 1) } }
        rule.waitForIdle()
        val pixels = rule.onNodeWithTag(FRAME).captureToImage().toPixelMap()

        assertEquals("the oldest row must reach the bar at full strength", ROW, pixels[COLUMN, 1])
    }

    /**
     * True when the sample sits between the row and the background. The two differ only in red, so
     * that channel alone reports how far the mask has dissolved the row towards the page.
     */
    private fun Color.isPartlyFaded(): Boolean = red > 0.3f && red < 0.7f

    /** Mirrors the transcript's geometry: reversed rows, a covered strip, and a known background. */
    private fun render() {
        rule.setContent {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            Box(Modifier.size(320.dp, 480.dp).background(BACKGROUND).testTag(FRAME)) {
                LazyColumn(
                    state = list,
                    reverseLayout = true,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .transcriptEdgeFade(listState = list, composerOverlap = COVERED_STRIP_DP.dp),
                ) {
                    items((0 until ROW_COUNT).toList(), key = { it }) {
                        Box(Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp).background(ROW))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private companion object {
        const val FRAME = "transcript-edge-fade-frame"
        const val SNAPSHOT = "src/test/snapshots/transcript_edge_fade.png"
        const val ROW_COUNT = 40
        const val ROW_HEIGHT_DP = 40
        const val COVERED_STRIP_DP = 60
        const val MIDDLE_ROW = 20
        const val COLUMN = 160

        /** Halfway across the band under the bar, where the mask should be about half strength. */
        const val TOP_BAND_MIDPOINT = 14

        /** Where the composer starts, 480 - 60, and so where the bottom fade begins. */
        const val COMPOSER_TOP_EDGE = 480 - COVERED_STRIP_DP

        /** Halfway down the strip the composer covers, where the fade should be about half spent. */
        const val BEHIND_COMPOSER_MIDPOINT = 450
        val ROW = Color.Cyan
        val BACKGROUND = Color.White
    }
}
