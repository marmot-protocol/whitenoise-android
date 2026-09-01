package dev.ipf.whitenoise.android.ui.conversation

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.audio.tts.TtsSeekResult
import dev.ipf.whitenoise.android.audio.tts.TtsSpeechEngine
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudHighlightRangeKey
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudSentenceHighlightRangeKey
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        appState = appState()
        controller = ConversationController(appState = appState, initialGroup = group())
        appState.ttsController.attachEngine(engine)
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

    /**
     * Finds a real production-row width with the requested layout shape, then
     * compares every changed pixel with the selected sentence's character
     * cells. The expectation intentionally comes straight from glyph boxes,
     * independently of the production rectangle-merging implementation.
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

        val idle = captureFrame()
        speakSecondSentence(record)

        val highlightedLeaf = checkNotNull(leafCarryingHighlight())
        val sentenceRange = checkNotNull(highlightedLeaf.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey))
        assertEquals(SECOND_SENTENCE, highlightedLeaf.text().substring(sentenceRange.first, sentenceRange.last + 1))
        val layout = textLayout(highlightedLeaf)
        val leafBounds = highlightedLeaf.boundsInRoot
        val expectedCells =
            (sentenceRange.first..sentenceRange.last).map { offset ->
                layout.getBoundingBox(offset).translate(leafBounds.left, leafBounds.top)
            }
        val adjacentCells =
            ((0 until sentenceRange.first) + ((sentenceRange.last + 1) until THREE_SENTENCES.length)).map { offset ->
                layout.getBoundingBox(offset).translate(leafBounds.left, leafBounds.top)
            }
        val active = captureFrame()
        val changed = idle.changedPixels(active)
        assertTrue("the selected sentence painted no pixels at width $selectedWidth", changed.isNotEmpty())
        assertTrue(
            "sentence paint escaped its exact glyph cells at width $selectedWidth: " +
                changed.filterNot { pixel -> expectedCells.any { cell -> cell.contains(pixel.x, pixel.y) } }.take(12),
            changed.all { pixel -> expectedCells.any { cell -> cell.contains(pixel.x, pixel.y) } },
        )
        assertTrue(
            "paint entered the interior of the previous or next sentence at width $selectedWidth: " +
                changed
                    .filter { pixel ->
                        adjacentCells.any { cell -> cell.contains(pixel.x, pixel.y, tolerance = -RASTER_SEAM_PX) }
                    }.take(12),
            changed.none { pixel ->
                adjacentCells.any { cell -> cell.contains(pixel.x, pixel.y, tolerance = -RASTER_SEAM_PX) }
            },
        )
    }

    /** Starts playback and seeks the production controller to the second sentence. */
    private fun speakSecondSentence(record: AppMessageRecordFfi) {
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    parseMarkdown = { plainTextDocument(THREE_SENTENCES) },
                )!!
            }
        check(appState.ttsController.speak(listOf(entry), Locale.US))
        assertEquals(
            TtsSeekResult.Repositioned,
            appState.ttsController.seekToSentence(MESSAGE_ID, sentenceIndex = 1),
        )
        composeRule.waitForIdle()
    }

    /** Searches actual device font metrics for the sentence-start layout under test. */
    private fun selectProductionWidth(
        wrapWidth: MutableState<Dp>,
        placement: SentenceStartPlacement,
    ): Int =
        checkNotNull(
            (MIN_WRAP_WIDTH..MAX_WRAP_WIDTH).firstOrNull { candidate ->
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

    private data class PixelPoint(
        val x: Float,
        val y: Float,
    )

    private data class RenderedFrame(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    ) {
        /** Lists centers of pixels changed between equal-sized frames. */
        fun changedPixels(other: RenderedFrame): List<PixelPoint> {
            assertEquals(width, other.width)
            assertEquals(height, other.height)
            return pixels.indices
                .filter { pixels[it] != other.pixels[it] }
                .map { index -> PixelPoint(index % width + 0.5f, index / width + 0.5f) }
        }
    }

    private enum class SentenceStartPlacement {
        WrappedLineStart,
        MidLine,
    }

    /** Places a local character cell into root coordinates. */
    private fun androidx.compose.ui.geometry.Rect.translate(
        x: Float,
        y: Float,
    ): androidx.compose.ui.geometry.Rect =
        androidx.compose.ui.geometry
            .Rect(left + x, top + y, right + x, bottom + y)

    /** Pixel-center containment with one-pixel rasterization tolerance by default. */
    private fun androidx.compose.ui.geometry.Rect.contains(
        x: Float,
        y: Float,
        tolerance: Float = 1f,
    ): Boolean = x >= left - tolerance && x <= right + tolerance && y >= top - tolerance && y <= bottom + tolerance

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

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children + children.flatMap { it.descendants() }

    private fun SemanticsNode.text(): String =
        config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { annotated: AnnotatedString -> annotated.text }

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
            onQuickReactionsReset = {},
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

    private fun speakableRecord(plaintext: String) =
        AppMessageRecordFfi(
            messageIdHex = MESSAGE_ID,
            direction = "received",
            groupIdHex = GROUP_ID,
            sender = SENDER_ID,
            plaintext = plaintext,
            contentTokens = plainTextDocument(plaintext),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private fun plainTextDocument(text: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks = listOf(MarkdownBlockFfi.Paragraph(inlines = listOf(MarkdownInlineFfi.Text(text)))),
        )

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Read-aloud placement group",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private class ReplayEngine : TtsSpeechEngine {
        val submitted = mutableListOf<String>()
        private val spoken = mutableListOf<String>()
        private var current = 0
        private var rangeCallback: ((String?, Int, Int, Int) -> Unit)? = null
        private var startCallback: ((String?) -> Unit)? = null
        private var doneCallback: ((String?) -> Unit)? = null

        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

        override fun setSpeechRate(rate: Float) = Unit

        override fun setCallbacks(
            onStart: (String?) -> Unit,
            onDone: (String?) -> Unit,
            onError: (String?, Int) -> Unit,
            onRangeStart: (String?, Int, Int, Int) -> Unit,
            onStop: (String?, Boolean) -> Unit,
        ) {
            startCallback = onStart
            doneCallback = onDone
            rangeCallback = onRangeStart
        }

        override fun clearCallbacks() {
            rangeCallback = null
            startCallback = null
            doneCallback = null
        }

        /** Completes utterances until [chunkIndex] is the one being spoken. */
        fun advanceTo(chunkIndex: Int) {
            while (current < chunkIndex) {
                doneCallback?.invoke(spoken[current])
                current++
                startCallback?.invoke(spoken[current])
            }
        }

        override fun speak(
            text: String,
            utteranceId: String,
        ): Int {
            spoken += utteranceId
            submitted += text
            return TextToSpeech.SUCCESS
        }

        override fun stop() = Unit

        /**
         * The queue submits every chunk up front, so the utterance being spoken
         * is not the last one submitted. Addressing the wrong one is silently
         * rejected as stale, which looks exactly like a passing test.
         */
        fun range(
            chunkIndex: Int,
            start: Int,
            end: Int,
        ) {
            rangeCallback?.invoke(spoken[chunkIndex], start, end, 0)
        }
    }

    private companion object {
        const val SENDER_NAME = "Alice"
        const val PREFIX = "$SENDER_NAME: "
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_ID = "09" + "00".repeat(31)

        const val BODY = "Hello bright world of steady careful reading."
        val WORDS = listOf("Hello", "bright", "world", "steady", "careful", "reading")

        const val FIRST_SENTENCE = "The first sentence sits here."
        const val SECOND_SENTENCE = "The second one follows it."
        const val TWO_SENTENCES = "$FIRST_SENTENCE $SECOND_SENTENCE"
        const val THIRD_SENTENCE = "The third sentence stays clear."
        const val THREE_SENTENCES = "$FIRST_SENTENCE $SECOND_SENTENCE $THIRD_SENTENCE"
        val SECOND_SENTENCE_START = THREE_SENTENCES.indexOf(SECOND_SENTENCE)
        const val MIN_WRAP_WIDTH = 120
        const val MAX_WRAP_WIDTH = 480
        const val TEST_LOG_TAG = "WnTtsPlacement"
        const val RASTER_SEAM_PX = 1f
    }
}
