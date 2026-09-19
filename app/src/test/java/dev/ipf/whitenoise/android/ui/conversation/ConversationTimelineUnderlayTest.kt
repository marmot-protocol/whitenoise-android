package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsSentenceProjectionSegment
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Real native-list regressions for separate paint and clear geometry; no duplicate rows or synthetic layout info. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationTimelineUnderlayTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var viewport: ConversationTimelineViewport
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: ConversationScrollCoordinator
    private lateinit var writer: LazyListConversationScrollWriter
    private var foreground by mutableIntStateOf(64)
    private var panel by mutableIntStateOf(0)
    private var notice by mutableIntStateOf(0)
    private val observedReadingHeights = mutableListOf<Int>()
    private val mountedRows = mutableMapOf<Int, Any>()
    private val clickedRows = mutableListOf<Int>()
    private val sentenceLayouts = ConversationTtsSentenceLayoutRegistry()
    private val speechTarget = ConversationTtsFollowTarget(7L, "19", 0, 1, "review-sentence", 42uL)

    /** The same row paints into the margin while covered controls are absent and partial-row input remains usable. */
    @Test
    fun rowsPaintBehindRoundedComposerButOnlyClearPortionsAcceptInput() {
        foreground = 120
        render()
        rule.waitForIdle()
        // The reversed list rests with its newest row clear of the composer, so slide
        // that row under the overlay before asserting what the overlay covers.
        rule.runOnIdle { scope.launch { list.scrollToItem(0, COVERED_ROW_OVERLAP_PX) } }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(480, list.layoutInfo.viewportSize.height)
            assertEquals(360, viewport.readingLayoutInfo().viewportSize.height)
            assertEquals(8, viewport.readingLayoutInfo().beforeContentPadding)
            // Reversed rows: the newest key sits at the origin against the composer,
            // so it is the occluded one and its predecessor is the first readable row.
            assertTrue(list.layoutInfo.visibleItemsInfo.any { it.key == 19 })
            assertFalse(viewport.readingLayoutInfo().visibleItemsInfo.any { it.key == 19 })
            assertTrue(viewport.readingLayoutInfo().visibleItemsInfo.any { it.key == 18 })
        }
        rule.onNodeWithTag("row-$COVERED_ROW_VALUE").assertDoesNotExist()
        rule.onNodeWithTag("frame").performTouchInput { click(Offset(14f, 440f)) }
        assertTrue(clickedRows.isEmpty())
        // A row fully inside the clear band still accepts input. Reversed emission puts
        // value 17 two rows above the covered origin.
        rule.onNodeWithTag("row-$CLEAR_ROW_VALUE").performTouchInput { click(Offset(8f, 12f)) }
        assertEquals(listOf(CLEAR_ROW_VALUE), clickedRows)
        val pixels = rule.onNodeWithTag("frame").captureToImage().toPixelMap()
        assertEquals("the existing row must paint through the composer margin", Color.Cyan, pixels[14, 440])
        rule.onNodeWithTag("frame").captureRoboImage("src/test/snapshots/conversation_timeline_underlay.png")
    }

    /**
     * The read watermark follows the newest row the reader can actually see. Taken from the raw
     * layout it would instead follow the row the composer covers, silently clearing an unread
     * count for a message that never came into view.
     */
    @Test
    fun theReadAnchorCandidateSkipsTheRowBehindTheComposer() {
        foreground = 120
        render()
        rule.waitForIdle()
        rule.runOnIdle { scope.launch { list.scrollToItem(0, COVERED_ROW_OVERLAP_PX) } }
        rule.waitForIdle()
        rule.runOnIdle {
            // The anchor reads the first entry of whichever layout it is given; reversed emission
            // makes that the newest row.
            val rawCandidate = list.layoutInfo.visibleItemsInfo.first()
            val readingCandidate = checkNotNull(viewport.readingLayoutInfo().newestReadRow(TIMELINE_LIST_INDICES))
            assertEquals(COVERED_ROW_VALUE, rawCandidate.key)
            // This slide buries the newest row whole and still leaves the one behind it clipped by
            // the composer, so the watermark has to fall back a further row to find one read.
            assertEquals(COVERED_ROW_VALUE - 2, readingCandidate.key)
            assertEquals(
                TIMELINE_SIZE - 1,
                conversationTimelineIndexForListIndex(rawCandidate.index, TIMELINE_SIZE, trailingRowCount = 0),
            )
            assertEquals(
                TIMELINE_SIZE - 3,
                conversationTimelineIndexForListIndex(readingCandidate.index, TIMELINE_SIZE, trailingRowCount = 0),
            )
        }
    }

    /**
     * On open the transcript rests with the next message showing a sliver above the composer. That
     * sliver is a real touch and TalkBack target, but it is not something anyone has read, so it
     * must not become the read watermark and must not be deducted from the unread badge.
     */
    @Test
    fun aRowLeftShowingASliverIsNotTreatedAsRead() {
        foreground = 120
        render()
        rule.waitForIdle()
        rule.runOnIdle { scope.launch { list.scrollToItem(0, SLIVER_OVERLAP_PX) } }
        rule.waitForIdle()
        rule.runOnIdle {
            val reading = viewport.readingLayoutInfo()
            // The sliver keeps the row usable, which is what the shared projection is for.
            assertEquals(COVERED_ROW_VALUE, reading.visibleItemsInfo.first().key)
            // The watermark asks a stricter question and must refuse it.
            assertEquals(COVERED_ROW_VALUE - 1, reading.newestReadRow(TIMELINE_LIST_INDICES)?.key)
        }
    }

    /**
     * The transcript emits error, paging and spacer rows alongside messages. A trailing one of
     * those maps past the end of the timeline, where the caller's clamp would resolve it to the
     * newest message and mark the whole conversation read, so it must never be the candidate.
     */
    @Test
    fun aTrailingStructuralRowIsNeverTheReadCandidate() {
        foreground = 120
        render()
        rule.waitForIdle()
        rule.runOnIdle {
            val reading = viewport.readingLayoutInfo()
            assertEquals(COVERED_ROW_VALUE, reading.newestReadRow(TIMELINE_LIST_INDICES)?.key)
            // Excluding the newest slot is what a trailing structural row does to the message range.
            assertEquals(COVERED_ROW_VALUE - 1, reading.newestReadRow(1 until TIMELINE_SIZE)?.key)
        }
    }

    /** A row taller than the clear viewport is the only thing the reader can be looking at, so it counts. */
    @Test
    fun aRowTallerThanTheViewportStillCountsAsRead() {
        foreground = 120
        render(oversizedLastRow = true)
        rule.waitForIdle()
        rule.runOnIdle { scope.launch { list.scrollToItem(0, SLIVER_OVERLAP_PX) } }
        rule.waitForIdle()
        rule.runOnIdle {
            val reading = viewport.readingLayoutInfo()
            val newest = reading.newestReadRow(TIMELINE_LIST_INDICES)
            assertTrue(
                "an oversized row must not leave the watermark with nothing to advance to",
                newest != null,
            )
        }
    }

    /** Composer growth and keyboard/custom-panel exclusion preserve the native history bookmark and row instance. */
    @Test
    fun historyBookmarkSurvivesForegroundAndInputPanelChanges() {
        render()
        rule.runOnIdle { scope.launch { writer.scrollToItem(7, 23) } }
        rule.waitForIdle()
        val oldList = list
        val oldRow = mountedRows.getValue(7)
        rule.runOnIdle { foreground = 144 }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(480, list.layoutInfo.viewportSize.height)
            assertEquals(336, viewport.readingHeightPx())
            assertEquals(7, list.firstVisibleItemIndex)
            assertEquals(23, list.firstVisibleItemScrollOffset)
            panel = 160
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(320, list.layoutInfo.viewportSize.height)
            assertEquals(176, viewport.readingHeightPx())
            assertEquals(7, list.firstVisibleItemIndex)
            assertEquals(23, list.firstVisibleItemScrollOffset)
            assertSame(oldList, list)
            assertSame(oldRow, mountedRows.getValue(7))
            foreground = 64
            panel = 0
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(416, viewport.readingHeightPx())
            assertEquals(7, list.firstVisibleItemIndex)
            assertEquals(23, list.firstVisibleItemScrollOffset)
        }
    }

    /** Existing writer and inset reanchor effect keep an oversized tail above the actual foreground and input panel. */
    @Test
    fun oversizedTailUsesTheClearEdgeAcrossExpansionAndIme() {
        render(followTail = true, oversizedLastRow = true)
        rule.runOnIdle { scope.launch { writer.scrollToTail(0) } }
        rule.waitForIdle()
        assertTailAtClearEdge()
        rule.runOnIdle {
            foreground = 160
            panel = 120
        }
        rule.waitForIdle()
        assertTailAtClearEdge()
        rule.onNodeWithTag("frame").captureRoboImage("src/test/snapshots/conversation_timeline_underlay_ime.png")
        rule.runOnIdle {
            foreground = 64
            panel = 0
        }
        rule.waitForIdle()
        assertTailAtClearEdge()
    }

    /** Measured base clearance cannot be mistaken for overlay, even when the total native padding is unchanged. */
    @Test
    fun snackbarAndComposerExchangePaddingWithoutAFictitiousReadingFrame() {
        render()
        rule.waitForIdle()
        rule.runOnIdle {
            observedReadingHeights.clear()
            notice = 32
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(416, viewport.readingHeightPx())
            assertEquals(40, viewport.readingLayoutInfo().beforeContentPadding)
            assertTrue(observedReadingHeights.all { it == 416 })
            observedReadingHeights.clear()
            foreground = 96
            notice = 0
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(104, list.layoutInfo.beforeContentPadding)
            assertEquals(384, viewport.readingHeightPx())
            assertEquals(8, viewport.readingLayoutInfo().beforeContentPadding)
            assertTrue(observedReadingHeights.all { it == 416 || it == 384 })
        }
    }

    /** The real speech-follow command must correct a sentence painted completely behind foreground chrome. */
    @Test
    fun speechFollowMovesCoveredSentenceIntoTheMeasuredClearViewport() {
        foreground = 120
        render()
        rule.waitForIdle()
        // The reversed list rests with its newest row clear of the composer, so slide
        // that row under the overlay before asserting what the overlay covers.
        rule.runOnIdle { scope.launch { list.scrollToItem(0, COVERED_ROW_OVERLAP_PX) } }
        rule.waitForIdle()
        var followed = false
        rule.runOnIdle {
            assertTrue(checkNotNull(sentenceLayouts.completeSentenceBounds(speechTarget)).top >= 360f)
            scope.launch {
                followed =
                    followTtsTargetInViewport(
                        target = speechTarget,
                        direction = TtsFollowDirection.Forward,
                        itemKey = COVERED_ROW_VALUE,
                        targetIndex = 0,
                        estimatedItemHeightPx = 80,
                        listState = list,
                        timelineViewport = viewport,
                        scrollCoordinator = coordinator,
                        sentenceLayouts = sentenceLayouts,
                        claimPreposition = { true },
                        claimCorrectiveScroll = { true },
                        resolveTargetIndex = { 0 },
                        isCurrentTarget = { true },
                        currentScrollAnchor = {
                            ConversationScrollAnchor(
                                list.firstVisibleItemIndex,
                                list.firstVisibleItemScrollOffset,
                                "19",
                                "19",
                            )
                        },
                    )
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(followed)
            val sentence = checkNotNull(sentenceLayouts.completeSentenceBounds(speechTarget))
            val clear = checkNotNull(viewport.readingBoundsInWindow)
            assertTrue(sentence.top >= clear.top && sentence.bottom <= clear.bottom)
            assertEquals(480, list.layoutInfo.viewportSize.height)
        }
    }

    /** The production native tail writer must clamp against content padding without learning about a second list. */
    private fun assertTailAtClearEdge() {
        rule.runOnIdle {
            val clear = viewport.readingLayoutInfo()
            val tail = clear.visibleItemsInfo.single { it.key == 19 }
            assertTrue(abs(tail.offset - (clear.viewportStartOffset + clear.beforeContentPadding)) <= 1)
            assertFalse(list.canScrollBackward)
        }
    }

    /** Mirrors the production scaffold/composer measurement hooks with controlled real foreground and panel sizes. */
    @Suppress("LongMethod")
    private fun render(
        followTail: Boolean = false,
        oversizedLastRow: Boolean = false,
    ) {
        rule.setContent {
            WhiteNoiseTheme {
                list = rememberLazyListState()
                viewport = remember(list) { ConversationTimelineViewport(list) }
                scope = rememberCoroutineScope()
                LaunchedEffect(viewport) {
                    snapshotFlow { viewport.readingBoundsInWindow }.collect { bounds ->
                        if (bounds != null) sentenceLayouts.updateViewportBounds(bounds)
                    }
                }
                writer = remember(list) { LazyListConversationScrollWriter(list) }
                coordinator =
                    remember(writer) {
                        ConversationScrollCoordinator(
                            writer,
                            initialMode =
                                if (followTail) {
                                    ConversationScrollMode.FollowingTail
                                } else {
                                    ConversationScrollMode.ReadingHistory(null, 0)
                                },
                        )
                    }
                val density = LocalDensity.current
                SideEffect { viewport.enabled = true }
                LaunchedEffect(viewport) {
                    snapshotFlow { viewport.readingHeightPx() }.collect { observedReadingHeights += it }
                }
                Box(Modifier.size(320.dp, 480.dp).testTag("frame")) {
                    WhiteNoiseScaffold(
                        bottomBar = {
                            Column(Modifier.onSizeChanged { viewport.onBottomChromeMeasured(it.height) }) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(foreground.dp)
                                        .onSizeChanged { viewport.onComposerMeasured(it.height, 64) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Surface(
                                        Modifier.fillMaxSize(),
                                        shape = RoundedCornerShape(24.dp),
                                        color = Color.Black,
                                    ) {}
                                }
                                Spacer(Modifier.fillMaxWidth().height(panel.dp).background(Color.DarkGray))
                            }
                        },
                    ) { padding ->
                        val overlap = viewport.overlayPadding(density, enabled = true)
                        Box(Modifier.fillMaxSize().padding(conversationUnderlayScaffoldPadding(padding, overlap))) {
                            LazyColumn(
                                state = list,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp)
                                        .measureConversationTimelinePadding(viewport, 8.dp + notice.dp, overlap)
                                        .onGloballyPositioned(viewport::onPaintViewportMeasured),
                                reverseLayout = true,
                                contentPadding = conversationTimelineContentPadding(notice.dp, overlap),
                            ) {
                                items((0..19).toList().asReversed(), key = { it }) { index ->
                                    val rowInstance = remember(index) { Any() }
                                    mountedRows[index] = rowInstance
                                    if (index == COVERED_ROW_VALUE) {
                                        DisposableEffect(rowInstance) {
                                            sentenceLayouts.mountRow("19", rowInstance)
                                            onDispose { sentenceLayouts.unmountRow("19", rowInstance) }
                                        }
                                    }
                                    Box(Modifier.timelineReadingExposure(viewport)) {
                                        Text(
                                            "Message $index",
                                            Modifier
                                                .testTag("row-$index")
                                                .fillMaxWidth()
                                                .height(if (oversizedLastRow && index == 19) 600.dp else 80.dp)
                                                .background(Color.Cyan)
                                                .clickable { clickedRows += index }
                                                .onGloballyPositioned { coordinates ->
                                                    if (index == COVERED_ROW_VALUE) {
                                                        val position = coordinates.positionInWindow()
                                                        val coverage =
                                                            setOf(TtsSentenceProjectionSegment("plain", 0, 8))
                                                        sentenceLayouts.report(
                                                            ConversationTtsSentenceLayoutReport(
                                                                speechTarget,
                                                                rowInstance,
                                                                "plain",
                                                                Rect(
                                                                    position.x,
                                                                    position.y,
                                                                    position.x + coordinates.size.width,
                                                                    position.y + coordinates.size.height,
                                                                ),
                                                                coverage,
                                                                coverage,
                                                            ),
                                                        )
                                                    }
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reversed emission lays the newest value at the origin against the composer, so
 * that value is the covered row this harness exercises.
 */
private const val COVERED_ROW_VALUE = 19

/** The harness emits keys 0..19, so the timeline the read anchor indexes into is twenty rows long. */
private const val TIMELINE_SIZE = 20

/** This harness emits no error, paging or spacer rows, so every list index is a message row. */
private val TIMELINE_LIST_INDICES = 0 until TIMELINE_SIZE

/**
 * Pixels the newest row is slid under the composer so the overlay covers it whole.
 * Partial rows stay readable by design, so this clears the row's full 80px height
 * plus the resting gap beneath it.
 */
private const val COVERED_ROW_OVERLAP_PX = 100

/** Slides the newest 80px row most of the way under, leaving only a sliver in the clear band. */
private const val SLIVER_OVERLAP_PX = 72

/** A row sitting well inside the clear band once the origin is slid under the composer. */
private const val CLEAR_ROW_VALUE = 17
