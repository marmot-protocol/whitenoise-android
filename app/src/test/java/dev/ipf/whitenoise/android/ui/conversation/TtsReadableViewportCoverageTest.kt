package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsSentenceProjectionSegment
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
import java.io.File
import kotlin.math.abs

/**
 * Read aloud rests a sentence where the transcript's painted mask is fully opaque, not merely inside
 * the list's clear interval.
 *
 * Mounted on the production shape: a reversed list under a player header, the composer overlap
 * consumed as content padding, the real [transcriptEdgeFade] publishing its geometry to the
 * [ConversationTimelineViewport], and the speech interval fed to the sentence registry exactly as
 * the screen does. Each test walks the chain the invariant depends on: painted fade, measured
 * readable interval, sentence landing and the final reveal verification. A source check pins the
 * screen to that same wiring, since only the screen connects the fade owner to speech.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h480dp-mdpi")
class TtsReadableViewportCoverageTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var viewport: ConversationTimelineViewport
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: ConversationScrollCoordinator
    private val sentenceLayouts = ConversationTtsSentenceLayoutRegistry()
    private val target = ConversationTtsFollowTarget(7L, "message-$TARGET_ROW", 0, 1, "readable-sentence", 42uL)
    private var leafHeight by mutableIntStateOf(COMPACT_LEAF_DP)
    private var frameTop = 0f

    /**
     * Resume follow lands a sentence at the fade's opaque edge, so every pixel of its first line is
     * at full strength while the band above it is still visibly dimming older history.
     */
    @Test
    fun resumedFollowLandsTheFirstLineBelowThePaintedFade() {
        render()
        assertTrue("the follow must verify its own reveal", follow(anchorAtTop = true))

        val paint = paintBounds()
        val speech = speechBounds()
        val sentence = sentenceBounds()
        // What the listener sees comes first: the first line must not be dimmed at all.
        val pixels = rule.onNodeWithTag(FRAME).captureToImage().toPixelMap()
        val firstLine = (sentence.top - frameTop).toInt() + 1
        for (column in listOf(8, 160, 312)) {
            assertEquals("the first line must be at full strength at x=$column", SENTENCE, pixels[column, firstLine])
        }
        assertEquals("older history remains, so the settled top band is excluded", paint.top + FADE_DP, speech.top)
        assertEquals("the composer still occludes the bottom", paint.bottom - OVERLAP_DP, speech.bottom)
        assertTrue("sentence $sentence must start at the opaque edge of $speech", abs(sentence.top - speech.top) <= 1f)
        assertTrue(ttsSentenceWasRevealed(sentence, speech))
        val bandMidpoint = (paint.top - frameTop + FADE_DP / 2).toInt()
        assertTrue(
            "the band the sentence was kept out of must really be dimming the row above it",
            pixels[160, bandMidpoint].red > 0.3f && pixels[160, bandMidpoint].red < 0.8f,
        )
    }

    /**
     * A sentence the list considers visible but whose first line sits in the fade still needs a
     * reveal, and automatic follow moves it to the opaque edge with one correction, after which a
     * fully readable sentence stays still.
     */
    @Test
    fun aSentenceInsideTheFadeIsRevealedOnceAndThenLeftStill() {
        render()
        assertTrue(follow(anchorAtTop = true))
        // Park the sentence's first line halfway into the band, where the reading interval still contains it.
        val parkDelta = sentenceBounds().top - (paintBounds().top + FADE_DP / 2)
        rule.runOnIdle { scope.launch { list.scrollBy(-parkDelta) } }
        rule.waitForIdle()
        rule.runOnIdle {
            val sentence = sentenceBounds()
            assertTrue("the sentence is inside the list's clear interval", sentence.top >= readingBounds().top)
            assertTrue("but its first line is dimmed by the fade", sentenceLayouts.needsReveal(target))
        }

        assertTrue(follow(anchorAtTop = false))
        assertTrue(abs(sentenceBounds().top - speechBounds().top) <= 1f)
        assertFalse(sentenceLayouts.needsReveal(target))

        val settled = scrollPosition()
        assertTrue(follow(anchorAtTop = false))
        assertEquals("an already readable sentence must not jump", settled, scrollPosition())
    }

    /** A sentence taller than the readable interval still begins at the opaque edge, never in the band. */
    @Test
    fun anOversizedSentenceBeginsAtTheOpaqueEdge() {
        leafHeight = OVERSIZED_LEAF_DP
        render()
        assertTrue(follow(anchorAtTop = true))

        val speech = speechBounds()
        val sentence = sentenceBounds()
        assertTrue("the sentence must not fit, or this proves nothing", sentence.height > speech.height)
        assertTrue("sentence $sentence must begin at $speech", abs(sentence.top - speech.top) <= 1f)
        assertTrue(ttsSentenceWasRevealed(sentence, speech))
    }

    /** Once history runs out the fade switches off, and the whole reading interval is readable again. */
    @Test
    fun theReadableTopReturnsToThePaintTopWhenTheFadeSwitchesOff() {
        render()
        rule.runOnIdle { scope.launch { list.scrollToItem(ROW_COUNT - 1) } }
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse(list.canScrollForward)
            assertEquals(readingBounds(), speechBounds())
            assertEquals(speechBounds(), sentenceLayouts.viewportBoundsInWindow)
        }
    }

    /** The screen must hand speech the fade-aware interval and give the painting fade its geometry owner. */
    @Test
    fun theScreenFeedsSpeechTheIntervalOwnedByThePaintedFade() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        assertTrue(
            "the transcript's fade must publish to the viewport that speech reads",
            "geometry = timelineViewport.edgeFade" in screen,
        )
        assertTrue(
            "the sentence registry must receive the speech interval",
            Regex("""snapshotFlow \{ timelineViewport\.speechBoundsInWindow \}[\s\S]{0,200}updateViewportBounds""")
                .containsMatchIn(screen),
        )
        assertFalse(
            "the reading interval includes the fade and must not reach the sentence registry",
            Regex("""snapshotFlow \{ timelineViewport\.readingBoundsInWindow \}[\s\S]{0,300}updateViewportBounds""")
                .containsMatchIn(screen),
        )
        val follow = source("ui/conversation/ConversationTtsFollow.kt").functionBody("followTtsTargetInViewport")
        assertTrue(
            "alignment and the final reveal must both use the registry's interval",
            "measured.viewportBoundsInWindow.height" in follow &&
                "sentenceLayouts.viewportBoundsInWindow" in follow,
        )
    }

    /** Runs one follow request to completion and returns whether it verified the reveal. */
    private fun follow(anchorAtTop: Boolean): Boolean {
        var followed = false
        rule.runOnIdle {
            scope.launch {
                followed =
                    followTtsTargetInViewport(
                        target = target,
                        direction = TtsFollowDirection.Reverse,
                        anchorAtTop = anchorAtTop,
                        itemKey = TARGET_ROW,
                        targetIndex = TARGET_ROW,
                        estimatedItemHeightPx = null,
                        listState = list,
                        timelineViewport = viewport,
                        scrollCoordinator = coordinator,
                        sentenceLayouts = sentenceLayouts,
                        claimPreposition = { true },
                        claimCorrectiveScroll = { true },
                        resolveTargetIndex = { TARGET_ROW },
                        isCurrentTarget = { true },
                        currentScrollAnchor = {
                            val (index, offset) = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
                            ConversationScrollAnchor(index, offset, null, null)
                        },
                    )
            }
        }
        rule.waitForIdle()
        return followed
    }

    /** The native list's first visible row and its offset. */
    private fun scrollPosition(): Pair<Int, Int> =
        rule.runOnIdle {
            list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        }

    /** The target sentence's complete multi-leaf bounds, as the registry accepted them. */
    private fun sentenceBounds(): Rect = rule.runOnIdle { checkNotNull(sentenceLayouts.completeSentenceBounds(target)) }

    /** The interval speech may rest a sentence in. */
    private fun speechBounds(): Rect = rule.runOnIdle { checkNotNull(viewport.speechBoundsInWindow) }

    /** The clear interval every other transcript consumer uses. */
    private fun readingBounds(): Rect = rule.runOnIdle { checkNotNull(viewport.readingBoundsInWindow) }

    /** The list's actual painted bounds. */
    private fun paintBounds(): Rect =
        rule.runOnIdle {
            val reading = checkNotNull(viewport.readingBoundsInWindow)
            reading.copy(bottom = reading.top + list.layoutInfo.viewportSize.height)
        }

    /** Mirrors the production transcript: header, reversed list, consumed overlap and the real fade. */
    private fun render() {
        rule.setContent {
            list = rememberLazyListState()
            viewport = remember(list) { ConversationTimelineViewport(list) }
            scope = rememberCoroutineScope()
            coordinator =
                remember(list) {
                    ConversationScrollCoordinator(
                        writer = LazyListConversationScrollWriter(list),
                        initialMode = ConversationScrollMode.ReadingHistory(null, 0),
                    )
                }
            LaunchedEffect(viewport) {
                snapshotFlow { viewport.speechBoundsInWindow }.collect { bounds ->
                    if (bounds != null) sentenceLayouts.updateViewportBounds(bounds)
                }
            }
            Column(
                Modifier
                    .size(320.dp, 480.dp)
                    .background(BACKGROUND)
                    .testTag(FRAME)
                    .onGloballyPositioned { frameTop = it.positionInWindow().y },
            ) {
                Box(Modifier.fillMaxWidth().height(HEADER_DP.dp))
                LazyColumn(
                    state = list,
                    reverseLayout = true,
                    contentPadding = PaddingValues(bottom = (BASE_PADDING_DP + OVERLAP_DP).dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .measureConversationTimelinePadding(
                                viewport,
                                BASE_PADDING_DP.dp,
                                OVERLAP_DP.dp,
                                timelinePublication = emptyList(),
                            ).onGloballyPositioned(viewport::onPaintViewportMeasured)
                            .transcriptEdgeFade(
                                listState = list,
                                composerOverlap = OVERLAP_DP.dp,
                                geometry = viewport.edgeFade,
                            ),
                ) {
                    items((0 until ROW_COUNT).toList(), key = { it }) { index ->
                        if (index == TARGET_ROW) {
                            TargetRow()
                        } else {
                            Box(Modifier.fillMaxWidth().height(ROW_DP.dp).background(ROW))
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** A message whose sentence spans two rendered leaves, followed by more of the same message. */
    @Composable
    private fun TargetRow() {
        val rowInstance = remember { Any() }
        DisposableEffect(rowInstance) {
            sentenceLayouts.mountRow(target.messageIdHex, rowInstance)
            onDispose { sentenceLayouts.unmountRow(target.messageIdHex, rowInstance) }
        }
        Column(Modifier.fillMaxWidth()) {
            SentenceLeaf(rowInstance, "leaf-a", TtsSentenceProjectionSegment("leaf-a", 0, 8))
            SentenceLeaf(rowInstance, "leaf-b", TtsSentenceProjectionSegment("leaf-b", 8, 16))
            Box(Modifier.fillMaxWidth().height(ROW_DP.dp * 2).background(ROW))
        }
    }

    /** One rendered leaf of the target sentence, reporting its window bounds like the production row. */
    @Composable
    private fun SentenceLeaf(
        rowInstance: Any,
        leafId: String,
        coverage: TtsSentenceProjectionSegment,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(leafHeight.dp)
                .background(SENTENCE)
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    sentenceLayouts.report(
                        ConversationTtsSentenceLayoutReport(
                            target = target,
                            rowInstance = rowInstance,
                            renderedLeafId = leafId,
                            boundsInWindow =
                                Rect(
                                    position.x,
                                    position.y,
                                    position.x + coordinates.size.width,
                                    position.y + coordinates.size.height,
                                ),
                            coverage = setOf(coverage),
                            expectedCoverage = SENTENCE_COVERAGE,
                        ),
                    )
                },
        )
    }

    /** Reads a production source file relative to either the module or the repository root. */
    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing source file: $relativePath")

    private companion object {
        const val FRAME = "tts-readable-viewport-frame"
        const val ROW_COUNT = 40
        const val TARGET_ROW = 20
        const val ROW_DP = 60
        const val HEADER_DP = 48
        const val BASE_PADDING_DP = 8
        const val OVERLAP_DP = 60f
        const val COMPACT_LEAF_DP = 24
        const val OVERSIZED_LEAF_DP = 220

        /** The production fade height at mdpi, where one dp is one pixel. */
        val FADE_DP = TRANSCRIPT_EDGE_FADE_HEIGHT.value
        val SENTENCE_COVERAGE =
            setOf(TtsSentenceProjectionSegment("leaf-a", 0, 8), TtsSentenceProjectionSegment("leaf-b", 8, 16))
        val ROW = Color.Cyan
        val SENTENCE = Color.Blue
        val BACKGROUND = Color.White
    }
}
