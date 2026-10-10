package dev.ipf.whitenoise.android.ui.conversation

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.audio.tts.TtsSeekResult
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.BODY
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.FIRST_SENTENCE
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.MAX_WRAP_WIDTH
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.MESSAGE_ID
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.MIN_WRAP_WIDTH
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.PREFIX
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.RTL_SECOND_SENTENCE
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.RTL_THREE_SENTENCES
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.RenderedFrame
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.ReplayEngine
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.SECOND_SENTENCE
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.SECOND_SENTENCE_START
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.SENDER_NAME
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.SentenceStartPlacement
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.TEST_LOG_TAG
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.THREE_SENTENCES
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.TWO_SENTENCES
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.WORDS
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.WRAP_WIDTH_STEP_DP
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.appState
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.contains
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.descendants
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.group
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.markdownSentenceDocument
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.plainTextDocument
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.speakableRecord
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.text
import dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementFixtures.translate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudHighlightRangeKey
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudSentenceHighlightRangeKey
import dev.ipf.whitenoise.android.ui.conversation.messages.highlightBoundingBoxes
import dev.ipf.whitenoise.android.ui.conversation.messages.ttsHighlightTextRange
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Placement, not merely presence.
 *
 * `TimelineRowTtsHighlightPaintAndroidTest` proves the painter runs by counting
 * changed pixels, which stays green for a highlight drawn over the wrong word.
 * This drives known engine ranges through the production row on a device and
 * asserts where the highlight landed: that the word marker covers exactly the
 * spoken characters, that the sentence band stops at the sentence being spoken,
 * and that each word's marker occupies its own place under real font metrics.
 */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class TtsHighlightPlacementAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val engine = ReplayEngine()
    private lateinit var appState: WhiteNoiseAppState
    private lateinit var controller: ConversationController
    private val composerTextState = ComposerTextState(TextFieldValue(""))

    @Before
    fun setUp() {
        appState = appState(context)
        controller = ConversationController(appState = appState, initialGroup = group())
        appState.ttsController.attachEngine(engine)
    }

    @After
    fun stopPlayback() {
        appState.ttsController.stop()
        appState.ttsController.detachEngine()
    }

    /** The production row keeps a sentence band when an engine reports no native ranges. */
    @Test
    fun rangeSilentPlaybackKeepsTheSentenceAndMapsEstimatedWords() {
        startSpeaking(TWO_SENTENCES)
        assertEquals(FIRST_SENTENCE, renderedSentenceBand())
        engine.start(0)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            appState.ttsController.state.value.passage
                ?.visibleWord
                .orEmpty()
                .isNotEmpty()
        }
        composeRule.waitForIdle()
        val leaf = checkNotNull(leafCarryingHighlight())
        val word = checkNotNull(leaf.config.getOrNull(TtsReadAloudHighlightRangeKey))
        assertTrue(word.first >= 0 && word.last < FIRST_SENTENCE.length)
        assertTrue(leaf.text().substring(word.first, word.last + 1).isNotBlank())
        assertEquals(FIRST_SENTENCE, renderedSentenceBand())
    }

    /** Stopping revokes the active passage even when native callbacks arrive afterwards. */
    @Test
    fun stopClearsTheRenderedPassageAndRejectsLateEngineCallbacks() {
        startSpeaking(TWO_SENTENCES)
        rangeWithin(chunkIndex = 0, word = "first")
        assertNotNull(leafCarryingHighlight())
        appState.ttsController.stop()
        engine.range(0, PREFIX.length, PREFIX.length + 4)
        engine.start(0)
        engine.done(0)
        composeRule.waitForIdle()

        assertNull(appState.ttsController.state.value.passage)
        assertNull(leafCarryingHighlight())
    }

    /** Pause retains sentence context; resumed playback refuses the superseded utterance's ranges. */
    @Test
    fun resumedPlaybackRetainsTheSentenceAndIgnoresOldWordRanges() {
        startSpeaking(TWO_SENTENCES)
        rangeWithin(chunkIndex = 0, word = "first")
        val session = appState.ttsController.state.value.sessionId
        appState.ttsController.pause()
        composeRule.waitForIdle()
        assertEquals(FIRST_SENTENCE, renderedSentenceBand())
        val resumedIndex = engine.submitted.size
        appState.ttsController.resume()
        rangeWithin(chunkIndex = resumedIndex, word = "sentence")
        val currentPassage = appState.ttsController.state.value.passage

        engine.range(0, PREFIX.length, PREFIX.length + 4)
        engine.done(0)
        composeRule.waitForIdle()

        assertEquals(session, appState.ttsController.state.value.sessionId)
        assertEquals(currentPassage, appState.ttsController.state.value.passage)
        assertEquals(FIRST_SENTENCE, renderedSentenceBand())
    }

    @Test
    fun eachEngineWordHighlightsExactlyThatWord() {
        startSpeaking(BODY)

        for (word in WORDS) {
            rangeWithin(chunkIndex = 0, word = word)

            val leaf = leafCarryingHighlight()
            assertNotNull("no rendered highlight for \"$word\"", leaf)
            val rendered = leaf!!.text()
            val range = leaf.config.getOrNull(TtsReadAloudHighlightRangeKey)
            assertNotNull("word \"$word\" produced no word range", range)
            assertEquals(
                "word marker landed on the wrong characters for \"$word\"",
                word,
                rendered.substring(range!!.first, range.last + 1),
            )
        }
    }

    @Test
    fun theSentenceBandFollowsTheSentenceBeingSpoken() {
        startSpeaking(TWO_SENTENCES)

        // Sentence boundaries make each sentence its own utterance, and the
        // queue submits both up front. Address the one actually speaking.
        rangeWithin(chunkIndex = 0, word = "first")
        assertEquals(
            "the band covers more than the sentence being spoken",
            FIRST_SENTENCE,
            renderedSentenceBand(),
        )

        engine.advanceTo(1)
        rangeWithin(chunkIndex = 1, word = "second")
        assertEquals(
            "the band did not follow playback onto the second sentence",
            SECOND_SENTENCE,
            renderedSentenceBand(),
        )
    }

    /** Exact sentence paint must begin on the wrapped line that owns its first character. */
    @Test
    fun sentenceBandExcludesAdjacentTextAtAWrappedLineBoundary() {
        assertExactSecondSentenceBand(SentenceStartPlacement.WrappedLineStart)
    }

    /** Exact sentence paint must retain its in-line start instead of expanding to the line edge. */
    @Test
    fun sentenceBandExcludesAdjacentTextWhenTheSentenceStartsMidLine() {
        assertExactSecondSentenceBand(SentenceStartPlacement.MidLine)
    }

    /** Markdown styling cannot expand the selected sentence into adjacent inline leaves. */
    @Test
    fun markdownSentenceBandPaintsOnlyItsRenderedCharacterCells() {
        assertExactRenderedSentenceBand(
            body = THREE_SENTENCES,
            document = markdownSentenceDocument(),
            expectedSentence = SECOND_SENTENCE,
            width = 300.dp,
            locale = Locale.US,
            layoutDirection = LayoutDirection.Ltr,
        )
    }

    /** RTL paragraph geometry keeps the selected sentence on its rendered right-to-left cells. */
    @Test
    fun rtlSentenceBandPaintsOnlyItsRenderedCharacterCells() {
        assertExactRenderedSentenceBand(
            body = RTL_THREE_SENTENCES,
            document = plainTextDocument(RTL_THREE_SENTENCES),
            expectedSentence = RTL_SECOND_SENTENCE,
            width = 300.dp,
            locale = Locale.forLanguageTag("he"),
            layoutDirection = LayoutDirection.Rtl,
            expectedParagraphDirection = ResolvedTextDirection.Rtl,
        )
    }

    /**
     * Finds a real production-row width with the requested layout shape, then
     * compares the production paint rectangles with the selected sentence's
     * character cells. The expectation intentionally comes straight from glyph
     * boxes, independently of the production rectangle-merging implementation.
     */
    private fun assertExactSecondSentenceBand(placement: SentenceStartPlacement) {
        val wrapWidth = mutableStateOf(MIN_WRAP_WIDTH.dp)
        val record = speakableRecord(THREE_SENTENCES)
        composeRule.setContent {
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                Box(Modifier.width(wrapWidth.value)) {
                    key(item.record.messageIdHex) { row(item) }
                }
            }
        }
        composeRule.waitForIdle()

        val selectedWidth = selectProductionWidth(wrapWidth, placement)
        Log.i(TEST_LOG_TAG, "sentenceBand placement=$placement wrapWidthDp=$selectedWidth")

        speakSecondSentence(record)

        val highlightedLeaf = checkNotNull(leafCarryingHighlight())
        val sentenceRange = checkNotNull(highlightedLeaf.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey))
        assertEquals(SECOND_SENTENCE, highlightedLeaf.text().substring(sentenceRange.first, sentenceRange.last + 1))
        val layout = textLayout(highlightedLeaf)
        val leafBounds = highlightedLeaf.boundsInRoot
        val expectedCells =
            (sentenceRange.first..sentenceRange.last)
                .map { offset ->
                    layout.getBoundingBox(offset).translate(leafBounds.left, leafBounds.top)
                }.filter { cell ->
                    cell.width > 0f && cell.height > 0f
                }
        val expectedPaintBoxes =
            expectedCells
                .groupBy { cell -> cell.top to cell.bottom }
                .values
                .map { lineCells ->
                    androidx.compose.ui.geometry.Rect(
                        left = lineCells.minOf { it.left },
                        top = lineCells.first().top,
                        right = lineCells.maxOf { it.right },
                        bottom = lineCells.first().bottom,
                    )
                }
        val productionPaintBoxes =
            highlightBoundingBoxes(
                layout,
                ttsHighlightTextRange(sentenceRange, layout.layoutInput.text.length),
            ).map { highlightBox ->
                highlightBox.bounds.translate(leafBounds.left, leafBounds.top)
            }
        assertEquals(
            "production sentence boxes escaped the selected glyph cells at width $selectedWidth",
            expectedPaintBoxes,
            productionPaintBoxes,
        )
    }

    /** Compares a rich or bidi production-row sentence paint with its exact character cells. */
    private fun assertExactRenderedSentenceBand(
        body: String,
        document: MarkdownDocumentFfi,
        expectedSentence: String,
        width: Dp,
        locale: Locale,
        layoutDirection: LayoutDirection,
        expectedParagraphDirection: ResolvedTextDirection? = null,
    ) {
        val record = speakableRecord(body, document)
        composeRule.setContent {
            val item = timelineMessage(record)
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme {
                    Box(Modifier.width(width)) {
                        key(item.record.messageIdHex) { row(item) }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val idle = captureFrame()
        speakSentence(record, document, locale, sentenceIndex = 1)
        val highlightedLeaves = leavesCarryingSentenceHighlight()
        assertTrue("no rendered leaf carried the selected sentence", highlightedLeaves.isNotEmpty())
        val renderedSentence =
            highlightedLeaves.joinToString("") { leaf ->
                val range = checkNotNull(leaf.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey))
                leaf.text().substring(range.first, range.last + 1)
            }
        assertEquals(expectedSentence, renderedSentence)

        val expectedCells =
            highlightedLeaves.flatMap { leaf ->
                val range = checkNotNull(leaf.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey))
                val layout = textLayout(leaf)
                val leafBounds = leaf.boundsInRoot
                if (expectedParagraphDirection != null) {
                    assertEquals(expectedParagraphDirection, layout.getParagraphDirection(range.first))
                }
                range.map { offset ->
                    layout.getBoundingBox(offset).translate(leafBounds.left, leafBounds.top)
                }
            }
        val active = captureFrame()
        val changed = idle.changedPixels(active)
        assertTrue("the selected sentence painted no pixels", changed.isNotEmpty())
        assertTrue(
            "sentence paint escaped its Markdown/RTL character cells: " +
                changed.filterNot { pixel -> expectedCells.any { cell -> cell.contains(pixel.x, pixel.y) } }.take(12),
            changed.all { pixel -> expectedCells.any { cell -> cell.contains(pixel.x, pixel.y) } },
        )
    }

    /** Starts playback and seeks the production controller to the second sentence. */
    private fun speakSecondSentence(record: AppMessageRecordFfi) {
        speakSentence(record, plainTextDocument(THREE_SENTENCES), Locale.US, sentenceIndex = 1)
    }

    /** Starts playback from the requested logical sentence for a rendered document. */
    private fun speakSentence(
        record: AppMessageRecordFfi,
        document: MarkdownDocumentFfi,
        locale: Locale,
        sentenceIndex: Int,
    ) {
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    parseMarkdown = { document },
                )!!
            }
        check(appState.ttsController.speak(listOf(entry), locale))
        assertEquals(
            TtsSeekResult.Repositioned,
            appState.ttsController.seekToSentence(MESSAGE_ID, sentenceIndex),
        )
        composeRule.waitForIdle()
    }

    /** Searches actual device font metrics for the sentence-start layout under test. */
    private fun selectProductionWidth(
        wrapWidth: MutableState<Dp>,
        placement: SentenceStartPlacement,
    ): Int =
        checkNotNull(
            (MIN_WRAP_WIDTH..MAX_WRAP_WIDTH step WRAP_WIDTH_STEP_DP).firstOrNull { candidate ->
                composeRule.runOnIdle { wrapWidth.value = candidate.dp }
                composeRule.waitForIdle()
                val layout = textLayout(bodyLeaf())
                val startBox = layout.getBoundingBox(SECOND_SENTENCE_START)
                val visualLine = layout.getLineForVerticalPosition(startBox.center.y)
                when (placement) {
                    SentenceStartPlacement.WrappedLineStart ->
                        visualLine > 0 && layout.getLineStart(visualLine) == SECOND_SENTENCE_START

                    SentenceStartPlacement.MidLine ->
                        layout.getLineStart(visualLine) < SECOND_SENTENCE_START
                }
            },
        ) { "no production-row width produced $placement" }

    /** Reports [word] at its offset inside the exact payload of [chunkIndex]. */
    private fun rangeWithin(
        chunkIndex: Int,
        word: String,
    ) {
        val payload = engine.submitted[chunkIndex]
        val offset = payload.indexOf(word)
        check(offset >= 0) { "'$word' is not in engine payload '$payload'" }
        engine.range(chunkIndex, offset, offset + word.length)
        composeRule.waitForIdle()
    }

    /**
     * The assertion real font metrics can fail and the JVM suite cannot make.
     *
     * The semantics range above is computed from the projection mapping and is
     * layout-independent, so Robolectric already covers it. What only a device
     * can answer is whether the paint lands under the word those coordinates
     * name: the word marker is a 2dp underline positioned from
     * `TextLayoutResult`, and a marker drawn at the wrong offset — or pinned to
     * one position for every word — still changes pixels and still passes a
     * `changedPixelCount > 0` check.
     */
    @Test
    fun eachWordMarkerIsPaintedInItsOwnPlace() {
        startSpeaking(BODY)
        val baseline = renderedPixels()

        // Bounding box, not just the horizontal span: a wrapped line legitimately
        // restarts at the same left margin, so x alone cannot tell "moved to the
        // next line" from "never moved".
        val placements =
            WORDS.associateWith { word ->
                val marker = markerBoxAfterSpeaking(word, baseline)
                MarkerPlacement(marker = marker, expected = expectedWordBounds(word))
            }
        placements.forEach { (word, placement) ->
            assertNotNull("highlighting \"$word\" painted nothing", placement.marker)
            assertMarkerInsideExpectedWord(word, placement.marker!!, placement.expected)
        }
        assertEquals(
            "distinct words share a marker box, so the paint is not following the word: $placements",
            WORDS.size,
            placements.values
                .map { it.marker }
                .toSet()
                .size,
        )
    }

    /** Bounding box of the pixels this word's marker changed, or null. */
    private fun markerBoxAfterSpeaking(
        word: String,
        baseline: IntArray,
    ): MarkerBox? {
        rangeWithin(chunkIndex = 0, word = word)
        val pixelMap = composeRule.onRoot(useUnmergedTree = true).captureToImage().toPixelMap()
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (index in baseline.indices) {
            val x = index % pixelMap.width
            val y = index / pixelMap.width
            if (baseline[index] != pixelMap[x, y].toArgb()) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (left <= right) MarkerBox(left, right, top, bottom) else null
    }

    private data class MarkerBox(
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
    )

    private data class MarkerPlacement(
        val marker: MarkerBox?,
        val expected: androidx.compose.ui.geometry.Rect,
    )

    private fun expectedWordBounds(word: String): androidx.compose.ui.geometry.Rect {
        val leaf = checkNotNull(leafCarryingHighlight()) { "no highlighted leaf for '$word'" }
        val range = checkNotNull(leaf.config.getOrNull(TtsReadAloudHighlightRangeKey))
        val layout = textLayout(leaf)
        val localBounds =
            range
                .map(layout::getBoundingBox)
                .reduce { accumulated, character ->
                    androidx.compose.ui.geometry.Rect(
                        left = minOf(accumulated.left, character.left),
                        top = minOf(accumulated.top, character.top),
                        right = maxOf(accumulated.right, character.right),
                        bottom = maxOf(accumulated.bottom, character.bottom),
                    )
                }
        val leafBounds = leaf.boundsInRoot
        return androidx.compose.ui.geometry.Rect(
            left = leafBounds.left + localBounds.left,
            top = leafBounds.top + localBounds.top,
            right = leafBounds.left + localBounds.right,
            bottom = leafBounds.top + localBounds.bottom,
        )
    }

    private fun assertMarkerInsideExpectedWord(
        word: String,
        marker: MarkerBox,
        expected: androidx.compose.ui.geometry.Rect,
    ) {
        val tolerance = 1f
        assertTrue(
            "marker for '$word' does not intersect its rendered glyph bounds: marker=$marker expected=$expected",
            marker.right >= expected.left - tolerance &&
                marker.left <= expected.right + tolerance &&
                marker.bottom >= expected.top - tolerance &&
                marker.top <= expected.bottom + tolerance,
        )
        assertTrue(
            "marker for '$word' is not horizontally contained by its rendered glyph bounds: " +
                "marker=$marker expected=$expected",
            marker.left >= expected.left - tolerance && marker.right <= expected.right + tolerance,
        )
    }

    /** Captures the current frame as an array used by the existing word-marker checks. */
    private fun renderedPixels(): IntArray = captureFrame().pixels

    /** Returns the production text leaf before or after the highlight modifier is active. */
    private fun bodyLeaf(): SemanticsNode =
        checkNotNull(
            composeRule
                .onRoot(useUnmergedTree = true)
                .fetchSemanticsNode()
                .descendants()
                .firstOrNull { it.text() == THREE_SENTENCES },
        ) { "production row exposed no body leaf" }

    /** Reads the exact TextLayoutResult exposed by the production text leaf. */
    private fun textLayout(leaf: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val getLayout =
            checkNotNull(leaf.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action) {
                "body leaf exposes no text layout"
            }
        assertTrue("text layout action failed", getLayout(layouts))
        return layouts.single()
    }

    /** Captures a root-sized frame so exact changed-pixel coordinates remain comparable. */
    private fun captureFrame(): RenderedFrame {
        val pixelMap = composeRule.onRoot(useUnmergedTree = true).captureToImage().toPixelMap()
        return RenderedFrame(
            width = pixelMap.width,
            height = pixelMap.height,
            pixels =
                IntArray(pixelMap.width * pixelMap.height) { index ->
                    pixelMap[index % pixelMap.width, index / pixelMap.width].toArgb()
                },
        )
    }

    private fun renderedSentenceBand(): String {
        val leaf = leafCarryingHighlight()
        assertNotNull("no rendered highlight", leaf)
        val rendered = leaf!!.text()
        val sentence = leaf.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey)
        assertNotNull("no sentence range was rendered", sentence)
        return rendered.substring(sentence!!.first, sentence.last + 1)
    }

    private fun startSpeaking(body: String) {
        val record = speakableRecord(body)
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    parseMarkdown = { plainTextDocument(body) },
                )!!
            }
        composeRule.setContent {
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    key(item.record.messageIdHex) { row(item) }
                }
            }
        }
        composeRule.waitForIdle()
        check(appState.ttsController.speak(listOf(entry), Locale.US))
        composeRule.waitForIdle()
    }

    private fun leafCarryingHighlight(): SemanticsNode? =
        composeRule
            .onRoot(useUnmergedTree = true)
            .fetchSemanticsNode()
            .descendants()
            .firstOrNull { node ->
                node.config.getOrNull(TtsReadAloudHighlightRangeKey) != null ||
                    node.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey) != null
            }

    /** Returns every rich-text leaf carrying part of the active sentence band in visual order. */
    private fun leavesCarryingSentenceHighlight(): List<SemanticsNode> =
        composeRule
            .onRoot(useUnmergedTree = true)
            .fetchSemanticsNode()
            .descendants()
            .filter { it.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey) != null }

    @Composable
    @Suppress("FunctionNaming", "LongMethod")
    private fun row(item: TimelineMessage) {
        TimelineRowMessageBubble(
            messageIdHex = item.record.messageIdHex,
            item = item,
            controller = controller,
            appState = appState,
            composerTextState = composerTextState,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = false,
            selected = false,
            onToggleSelection = {},
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = emptyList(),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = false,
            onActionMenuOpenChange = {},
            onQuickReactionsSave = {},
            onReplyPreviewClick = {},
            composerGate = ComposerGate.COMPOSER,
            onBack = {},
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
            showSenderName = false,
            showSenderAvatar = false,
            collapseLongMessages = false,
            readOnly = false,
            parseMarkdown = { item.record.contentTokens },
        )
    }

    private fun timelineMessage(record: AppMessageRecordFfi) =
        TimelineMessage(id = "msg:${record.messageIdHex}", record = record, status = MessageStatus.Received)
}
