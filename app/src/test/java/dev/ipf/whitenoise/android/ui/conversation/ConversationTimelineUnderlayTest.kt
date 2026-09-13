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
    private val speechTarget = ConversationTtsFollowTarget(7L, "5", 0, 1, "review-sentence", 42uL)

    /** The same row paints into the margin while covered controls are absent and partial-row input remains usable. */
    @Test
    fun rowsPaintBehindRoundedComposerButOnlyClearPortionsAcceptInput() {
        foreground = 120
        render()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(480, list.layoutInfo.viewportSize.height)
            assertEquals(360, viewport.readingLayoutInfo().viewportSize.height)
            assertEquals(8, viewport.readingLayoutInfo().afterContentPadding)
            assertTrue(list.layoutInfo.visibleItemsInfo.any { it.key == 5 })
            assertFalse(viewport.readingLayoutInfo().visibleItemsInfo.any { it.key == 5 })
            assertTrue(viewport.readingLayoutInfo().visibleItemsInfo.any { it.key == 4 })
        }
        rule.onNodeWithTag("row-5").assertDoesNotExist()
        rule.onNodeWithTag("frame").performTouchInput { click(Offset(14f, 440f)) }
        assertTrue(clickedRows.isEmpty())
        rule.onNodeWithTag("row-4").performTouchInput { click(Offset(8f, 12f)) }
        assertEquals(listOf(4), clickedRows)
        val pixels = rule.onNodeWithTag("frame").captureToImage().toPixelMap()
        assertEquals("the existing row must paint through the composer margin", Color.Cyan, pixels[14, 440])
        rule.onNodeWithTag("frame").captureRoboImage("src/test/snapshots/conversation_timeline_underlay.png")
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
        rule.runOnIdle { scope.launch { writer.scrollToTail(19) } }
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
            assertEquals(40, viewport.readingLayoutInfo().afterContentPadding)
            assertTrue(observedReadingHeights.all { it == 416 })
            observedReadingHeights.clear()
            foreground = 96
            notice = 0
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(104, list.layoutInfo.afterContentPadding)
            assertEquals(384, viewport.readingHeightPx())
            assertEquals(8, viewport.readingLayoutInfo().afterContentPadding)
            assertTrue(observedReadingHeights.all { it == 416 || it == 384 })
        }
    }

    /** The real speech-follow command must correct a sentence painted completely behind foreground chrome. */
    @Test
    fun speechFollowMovesCoveredSentenceIntoTheMeasuredClearViewport() {
        foreground = 120
        render()
        rule.waitForIdle()
        var followed = false
        rule.runOnIdle {
            assertTrue(checkNotNull(sentenceLayouts.completeSentenceBounds(speechTarget)).top >= 360f)
            scope.launch {
                followed =
                    followTtsTargetInViewport(
                        target = speechTarget,
                        direction = TtsFollowDirection.Forward,
                        itemKey = 5,
                        targetIndex = 5,
                        estimatedItemHeightPx = 80,
                        listState = list,
                        timelineViewport = viewport,
                        scrollCoordinator = coordinator,
                        sentenceLayouts = sentenceLayouts,
                        claimPreposition = { true },
                        claimCorrectiveScroll = { true },
                        resolveTargetIndex = { 5 },
                        isCurrentTarget = { true },
                        currentScrollAnchor = {
                            ConversationScrollAnchor(
                                list.firstVisibleItemIndex,
                                list.firstVisibleItemScrollOffset,
                                "5",
                                "5",
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
            assertTrue(abs(tail.offset + tail.size - (clear.viewportEndOffset - clear.afterContentPadding)) <= 1)
            assertFalse(list.canScrollForward)
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
                ConversationTailInsetReanchorEffect(
                    scrollCoordinator = coordinator,
                    bottomChromeHeightPx = foreground + panel,
                    snackbarContentInsetPx = notice,
                    bottomInputRevision = 0,
                    hasTimeline = true,
                    initialTimelineAnchored = true,
                    routePresentationFrozen = false,
                    foregroundRestoreInProgress = false,
                    currentTailIndex = { 19 },
                )
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
                                contentPadding = conversationTimelineContentPadding(notice.dp, overlap),
                            ) {
                                items((0..19).toList(), key = { it }) { index ->
                                    val rowInstance = remember(index) { Any() }
                                    mountedRows[index] = rowInstance
                                    if (index == 5) {
                                        DisposableEffect(rowInstance) {
                                            sentenceLayouts.mountRow("5", rowInstance)
                                            onDispose { sentenceLayouts.unmountRow("5", rowInstance) }
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
                                                    if (index == 5) {
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
