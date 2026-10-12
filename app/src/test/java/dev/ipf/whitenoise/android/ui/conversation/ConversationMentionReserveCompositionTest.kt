package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.TimelineMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A mention jump to the newest row writes its reading reserve before the tap's first frame (#3150).
 * These pin that the reserve reaches layout through measurement alone, so that frame relays the
 * transcript out instead of recomposing the screen hosting it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationMentionReserveCompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A reserve write that leaves the near-bottom flag unchanged does not recompose its caller. */
    @Test
    fun nearBottomReaderTakesReserveWithoutRecomposingTheCaller() {
        val listState = LazyListState()
        val reserve = mutableStateOf<Int?>(null)
        val nearBottom = arrayOf<Boolean?>(null)
        var callerCompositions = 0
        composeRule.setContent {
            SideEffect { callerCompositions++ }
            nearBottom[0] =
                rememberConversationNearBottom(
                    listState = listState,
                    renderedTimelineSize = HISTORY_SIZE,
                    trailingRowCount = 0,
                    timelineViewport = null,
                    mentionReadingRowHeightPx = { reserve.value },
                )
            HistoryHarness(listState)
        }
        scrollTo(listState, HISTORY_READING_INDEX)
        composeRule.runOnUiThread { assertFalse("reading history is not near the tail", nearBottom[0]!!) }
        val beforeReserve = callerCompositions

        composeRule.runOnUiThread { reserve.value = RESERVED_ROW_HEIGHT_PX }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertFalse(nearBottom[0]!!)
            assertEquals("the reserve is read inside the derived flag", beforeReserve, callerCompositions)
        }
    }

    /** The reader still withholds the tail flag while a reserve is held and restores it on release. */
    @Test
    fun nearBottomReaderStillReleasesTheTailFlagForAHeldReserve() {
        val listState = LazyListState()
        val reserve = mutableStateOf<Int?>(null)
        val nearBottom = arrayOf<Boolean?>(null)
        composeRule.setContent {
            nearBottom[0] =
                rememberConversationNearBottom(
                    listState = listState,
                    renderedTimelineSize = HISTORY_SIZE,
                    trailingRowCount = 0,
                    timelineViewport = null,
                    mentionReadingRowHeightPx = { reserve.value },
                )
            HistoryHarness(listState)
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { assertTrue("an unscrolled transcript rests at its tail", nearBottom[0]!!) }

        composeRule.runOnUiThread { reserve.value = RESERVED_ROW_HEIGHT_PX }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { assertFalse("a held reserve belongs to a reading anchor", nearBottom[0]!!) }

        composeRule.runOnUiThread { reserve.value = null }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { assertTrue(nearBottom[0]!!) }
    }

    /** The measure-time padding moves the newest row by the reserve with no host recomposition. */
    @Test
    fun measuredReserveMovesTheTailWithoutRecomposingTheHost() {
        val listState = LazyListState()
        val viewport = ConversationTimelineViewport(listState)
        val reserve = mutableStateOf(0.dp)
        var hostCompositions = 0
        composeRule.setContent {
            SideEffect { hostCompositions++ }
            val readReserve = remember { { reserve.value } }
            ReservedTranscript(
                listState = listState,
                contentPadding = remember { conversationTimelineContentPadding(0.dp, 0.dp, readReserve) },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .measureConversationTimelinePadding(
                            viewport,
                            CONVERSATION_TIMELINE_TAIL_GAP,
                            0.dp,
                            NO_PUBLICATION,
                            mentionReadingReserve = readReserve,
                        ),
            )
        }
        assertNewestRowGap(CONVERSATION_TIMELINE_TAIL_GAP)
        val beforeReserve = hostCompositions

        composeRule.runOnUiThread { reserve.value = RESERVE }
        composeRule.waitForIdle()

        assertNewestRowGap(CONVERSATION_TIMELINE_TAIL_GAP + RESERVE)
        composeRule.runOnUiThread { assertEquals(beforeReserve, hostCompositions) }
    }

    /** Control: reading the reserve in composition recomposes the host, so the probe above can fail. */
    @Test
    fun compositionReadOfTheReserveRecomposesTheHost() {
        val listState = LazyListState()
        val reserve = mutableStateOf(0.dp)
        var hostCompositions = 0
        composeRule.setContent {
            SideEffect { hostCompositions++ }
            ReservedTranscript(
                listState = listState,
                contentPadding = conversationTimelineContentPadding(0.dp, 0.dp, reserve.value),
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitForIdle()
        val beforeReserve = hostCompositions

        composeRule.runOnUiThread { reserve.value = RESERVE }
        composeRule.waitForIdle()

        assertNewestRowGap(CONVERSATION_TIMELINE_TAIL_GAP + RESERVE)
        composeRule.runOnUiThread { assertEquals(beforeReserve + 1, hostCompositions) }
    }

    /**
     * With a reserve derived from the reading layout, as the screen derives it, and composer overlap present, every
     * measure pass records the base its list padding used, so a growing viewport never misreports the overlap.
     */
    @Test
    fun layoutDerivedReserveRecordsTheBaseTheListUsedInEveryPass() {
        val listState = LazyListState()
        val viewport = ConversationTimelineViewport(listState)
        val height = mutableStateOf(SHORT_TRANSCRIPT_HEIGHT)
        val passes = mutableListOf<Pair<Int, Int>>()
        composeRule.setContent { LayoutDerivedReserveTranscript(listState, viewport, height.value, passes) }
        composeRule.waitForIdle()

        composeRule.runOnUiThread { height.value = TALL_TRANSCRIPT_HEIGHT }
        composeRule.waitForIdle()

        val overlapPx = with(composeRule.density) { OVERLAP.roundToPx() }
        composeRule.runOnUiThread {
            assertTrue("the probe sampled the viewport growth", passes.any { it.second > passes.first().second })
            passes.forEach { (readingHeight, listHeight) ->
                val clearHeight = (listHeight - overlapPx).toFloat()
                assertEquals("reading overlap in a $listHeight px pass", clearHeight, readingHeight.toFloat(), 1f)
            }
        }
        assertNewestRowGap(TALL_TRANSCRIPT_HEIGHT - ROW_HEIGHT)
    }

    /** The screen's reserve wiring over [ReservedTranscript], sampling the reading height after each measure. */
    @Composable
    private fun LayoutDerivedReserveTranscript(
        listState: LazyListState,
        viewport: ConversationTimelineViewport,
        height: Dp,
        passes: MutableList<Pair<Int, Int>>,
    ) {
        val density = LocalDensity.current
        val reserve =
            remember(viewport, density) {
                derivedStateOf {
                    with(density) {
                        conversationMentionReadingReservePx(
                            readingHeightPx = viewport.readingHeightPx(),
                            basePaddingPx = CONVERSATION_TIMELINE_TAIL_GAP.roundToPx(),
                            rowHeightPx = ROW_HEIGHT.roundToPx(),
                        ).toDp()
                    }
                }
            }
        val readReserve = remember(reserve) { { reserve.value } }
        ReservedTranscript(
            listState = listState,
            contentPadding = remember(readReserve) { conversationTimelineContentPadding(0.dp, OVERLAP, readReserve) },
            height = height,
            modifier =
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        // Outside the padding modifier, so each sample follows that pass's recorded base.
                        val placeable = measurable.measure(constraints)
                        passes += Snapshot.withoutReadObservation { viewport.readingHeightPx() } to placeable.height
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }.measureConversationTimelinePadding(
                        viewport,
                        CONVERSATION_TIMELINE_TAIL_GAP,
                        OVERLAP,
                        NO_PUBLICATION,
                        mentionReadingReserve = readReserve,
                    ),
        )
    }

    /** A reversed transcript of fixed-height rows, newest first, laid out against its bottom edge. */
    @Composable
    private fun ReservedTranscript(
        listState: LazyListState,
        contentPadding: PaddingValues,
        modifier: Modifier,
        height: Dp = TRANSCRIPT_HEIGHT,
    ) {
        Box(Modifier.height(height).testTag(TRANSCRIPT_TAG)) {
            LazyColumn(
                modifier = modifier,
                state = listState,
                reverseLayout = true,
                contentPadding = contentPadding,
            ) {
                items((0 until TRANSCRIPT_SIZE).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT)
                            .then(if (index == 0) Modifier.testTag(NEWEST_ROW_TAG) else Modifier),
                    )
                }
            }
        }
    }

    /** A history-length reversed list for the near-bottom flag, matching the scroll-intent fixtures. */
    @Composable
    private fun HistoryHarness(listState: LazyListState) {
        LazyColumn(modifier = Modifier.height(HISTORY_VIEWPORT), state = listState, reverseLayout = true) {
            items((0 until HISTORY_SIZE).toList().asReversed()) {
                Box(Modifier.fillMaxWidth().height(ROW_HEIGHT))
            }
        }
    }

    /** Requires the measured gap between the transcript bottom and its newest row to equal [expected]. */
    private fun assertNewestRowGap(expected: Dp) {
        val transcriptBottom =
            composeRule
                .onNodeWithTag(TRANSCRIPT_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val newestBottom =
            composeRule
                .onNodeWithTag(NEWEST_ROW_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertEquals(with(composeRule.density) { expected.toPx() }, transcriptBottom - newestBottom, 1f)
    }

    /** Scrolls through the native list and waits for the resulting layout. */
    private fun scrollTo(
        listState: LazyListState,
        index: Int,
    ) {
        composeRule.runOnUiThread { runBlocking { listState.scrollToItem(index) } }
        composeRule.waitForIdle()
    }
}

private const val TRANSCRIPT_TAG = "reserve-transcript"
private const val NEWEST_ROW_TAG = "reserve-newest-row"
private const val TRANSCRIPT_SIZE = 30
private const val HISTORY_SIZE = 50
private const val HISTORY_READING_INDEX = 20
private const val RESERVED_ROW_HEIGHT_PX = 120
private val TRANSCRIPT_HEIGHT = 400.dp
private val SHORT_TRANSCRIPT_HEIGHT = 300.dp
private val TALL_TRANSCRIPT_HEIGHT = 360.dp
private val OVERLAP = 60.dp
private val HISTORY_VIEWPORT = 100.dp
private val ROW_HEIGHT = 40.dp
private val RESERVE = 100.dp
private val NO_PUBLICATION = emptyList<TimelineMessage>()
