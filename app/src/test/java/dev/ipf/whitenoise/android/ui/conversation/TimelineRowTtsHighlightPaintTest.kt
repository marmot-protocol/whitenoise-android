package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudHighlightRangeKey
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudSentenceHighlightRangeKey
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleColumnTestTag
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Read-aloud highlights are user-visible paint, and every gate between
 * [dev.ipf.whitenoise.android.audio.tts.TtsController] and the leaf painter can
 * drop that paint while semantics, progress, and every range assertion stay
 * green. This drives a real controller passage through the production timeline
 * row and compares rendered pixels, so a highlight that never reaches the
 * screen fails here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TimelineRowTtsHighlightPaintTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val engine = FakePaintTtsSpeechEngine()
    private lateinit var appState: WhiteNoiseAppState
    private lateinit var controller: ConversationController
    private val sentenceLayouts = ConversationTtsSentenceLayoutRegistry()

    @Before
    fun setUp() {
        appState = paintTestAppState(context, ACCOUNT_REF, ACCOUNT_ID)
        controller = ConversationController(appState = appState, initialGroup = paintTestGroup(GROUP_ID, ACCOUNT_ID))
        appState.ttsController.attachEngine(engine)
    }

    @Test
    fun aQueuedMentionKeepsItsRenderedNameAndHighlightUntilTheSessionIsReplaced() {
        val key = "npub1" + "q".repeat(58)
        val doc =
            MarkdownDocumentFfi(
                truncated = false,
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Text("Hello "),
                                MarkdownInlineFfi.NostrMention(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, key)),
                            ),
                        ),
                    ),
                blankLinesBefore = byteArrayOf(),
            )
        val record = untokenizedRecord().copy(plaintext = "Hello nostr:$key", contentTokens = doc)

        fun entry(name: String) =
            runBlocking {
                projectTtsSpeakableEntry(record, null, SENDER_NAME, { doc }, { name })!!
            }

        renderProductionRow(record)
        check(appState.ttsController.speak(listOf(entry("Frozen Alice")), Locale.US))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hello @Frozen Alice").fetchSemanticsNode()
        val first =
            requireNotNull(
                appState.ttsController.state.value
                    .conversationFollowTargetOrNull(),
            )
        assertTrue(sentenceLayouts.completeSentenceBounds(first) != null)
        check(appState.ttsController.speak(listOf(entry("Changed Alice")), Locale.US))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hello @Changed Alice").fetchSemanticsNode()
        val target =
            requireNotNull(
                appState.ttsController.state.value
                    .conversationFollowTargetOrNull(),
            )
        assertTrue(sentenceLayouts.completeSentenceBounds(target) != null)
    }

    @Test
    fun plainRowPublishesEachSentenceWithoutChangingItsTextOrLayout() {
        val body = "First sentence. Second sentence."
        val record = untokenizedRecord().copy(plaintext = body)
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(record, null, SENDER_NAME, { emptyPaintDocument() })!!
            }
        renderProductionRow(record)
        check(appState.ttsController.speak(listOf(entry), Locale.US))
        composeRule.waitUntil(5_000) {
            val target =
                appState.ttsController.state.value
                    .conversationFollowTargetOrNull()
            target != null && sentenceLayouts.completeSentenceBounds(target) != null
        }
        val first =
            requireNotNull(
                appState.ttsController.state.value
                    .conversationFollowTargetOrNull(),
            )
        composeRule.runOnIdle { appState.ttsController.seekToSentence(entry.messageIdHex, 1, entry.projectionId) }
        composeRule.waitUntil(5_000) {
            val target =
                appState.ttsController.state.value
                    .conversationFollowTargetOrNull()
            target?.sentenceIndex == 1 && sentenceLayouts.completeSentenceBounds(target) != null
        }
        assertEquals(null, sentenceLayouts.completeSentenceBounds(first))
    }

    @Test
    fun speakingPassagePaintsSentenceAndWordHighlightThroughTheProductionRow() {
        val record = speakableRecord(MESSAGE_A, BODY)
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    parseMarkdown = { plainPaintDocument(BODY) },
                )!!
            }

        composeRule.setContent {
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    key(item.record.messageIdHex) {
                        row(item)
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val idlePixels = renderedPixels()

        check(appState.ttsController.speak(listOf(entry), Locale.US))
        composeRule.waitForIdle()
        val sentencePixels = renderedPixels()
        val sentenceDiagnostics = seamDiagnostics()

        engine.range(index = 0, start = WORD_START, end = WORD_END)
        composeRule.waitForIdle()
        val wordPixels = renderedPixels()
        val wordDiagnostics = seamDiagnostics()

        assertTrue(
            "Starting read-aloud painted nothing: the rendered row is pixel-identical to the idle row. " +
                "Seam state: $sentenceDiagnostics",
            changedPixelCount(idlePixels, sentencePixels) > 0,
        )
        assertTrue(
            "An engine word range painted nothing: the rendered row is unchanged by the active word. " +
                "Seam state: $wordDiagnostics",
            changedPixelCount(sentencePixels, wordPixels) > 0,
        )
    }

    @Test
    fun speakingPassagePaintsHighlightThroughRichMarkdownLeaves() {
        val record = richRecord()
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    parseMarkdown = { richPaintDocument() },
                )!!
            }

        composeRule.setContent {
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    key(item.record.messageIdHex) {
                        row(item)
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val idlePixels = renderedPixels()

        check(appState.ttsController.speak(listOf(entry), Locale.US))
        composeRule.waitForIdle()
        val speakingPixels = renderedPixels()
        val diagnostics = seamDiagnostics()

        assertTrue(
            "Read-aloud on a rich Markdown message painted nothing. Seam state: $diagnostics",
            changedPixelCount(idlePixels, speakingPixels) > 0,
        )
    }

    @Test
    fun collapsedPlainBodyRevealsItsLateSentenceForReadAloud() = assertCollapsedBodyRevealed(markdown = false)

    @Test
    fun collapsedMarkdownBodyRevealsItsLateSentenceForReadAloud() = assertCollapsedBodyRevealed(markdown = true)

    private fun assertCollapsedBodyRevealed(markdown: Boolean) {
        val body = (1..LONG_BODY_LINES).joinToString("\n") { "Sentence $it about bright things." }
        val document = if (markdown) plainPaintDocument(body) else emptyPaintDocument()
        val record = speakableRecord(MESSAGE_C, body).copy(contentTokens = document)
        val entry = runBlocking { projectTtsSpeakableEntry(record, null, SENDER_NAME, { document })!! }
        val next =
            runBlocking {
                projectTtsSpeakableEntry(
                    speakableRecord(MESSAGE_D, BODY),
                    null,
                    SENDER_NAME,
                    { plainPaintDocument(BODY) },
                )!!
            }
        renderProductionRow(record, collapseLongMessages = true)
        val readMore = composeRule.onNodeWithText(context.getString(R.string.message_read_more))
        readMore.assertExists()
        check(appState.ttsController.speak(listOf(entry, next), Locale.US))
        composeRule.waitForIdle()
        readMore.assertDoesNotExist()
        val first =
            requireNotNull(
                appState.ttsController.state.value
                    .conversationFollowTargetOrNull(),
            )
        assertTrue(
            "Expanded body must report its actual sentence",
            sentenceLayouts.completeSentenceBounds(first) != null,
        )
        val expandedHeight =
            collapsedRowHeight()
        composeRule.runOnIdle {
            appState.ttsController.seekToSentence(entry.messageIdHex, LONG_BODY_LINES - 1, entry.projectionId)
        }
        composeRule.waitForIdle()
        assertLateSentenceHighlighted(body)
        appState.ttsController.pause()
        composeRule.waitForIdle()
        readMore.assertDoesNotExist()
        composeRule.runOnIdle { appState.ttsController.seekToSentence(next.messageIdHex, 0, next.projectionId) }
        composeRule.waitForIdle()
        readMore.assertDoesNotExist()
        assertEquals(
            expandedHeight,
            collapsedRowHeight(),
        )
        appState.ttsController.stop()
        composeRule.waitForIdle()
        readMore.assertDoesNotExist()
        assertEquals(
            "Stopping speech must not shrink the row and move the user's viewport",
            expandedHeight,
            collapsedRowHeight(),
        )
    }

    private fun collapsedRowHeight() =
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(MESSAGE_C), useUnmergedTree = true)
            .fetchSemanticsNode()
            .size.height

    private fun assertLateSentenceHighlighted(body: String) {
        val last =
            requireNotNull(
                appState.ttsController.state.value
                    .conversationFollowTargetOrNull(),
            )
        assertEquals(LONG_BODY_LINES - 1, last.sentenceIndex)
        assertTrue("Formerly hidden sentence must have geometry", sentenceLayouts.completeSentenceBounds(last) != null)
        val range =
            composeRule
                .onNodeWithText(body, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config
                .getOrNull(TtsReadAloudSentenceHighlightRangeKey)
        assertTrue("Late sentence must be highlighted", range != null && !range.isEmpty())
        assertEquals("Sentence $LONG_BODY_LINES about bright things.", body.substring(range!!.first, range.last + 1))
    }

    /**
     * Verifies that read-aloud paints a highlight after production reparses a
     * legacy message that has no stored Markdown tokens.
     */
    @Test
    fun speakingPassagePaintsHighlightWhenTheRecordHasNoStoredContentTokens() {
        val record = untokenizedRecord()
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    // Production hands both sides the same parser. Giving the
                    // speaking side a stub would let the two projections
                    // diverge for a reason the app never has.
                    parseMarkdown = { appState.parseMarkdownOrEmpty(it) },
                )!!
            }

        composeRule.setContent {
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    key(item.record.messageIdHex) {
                        row(item)
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val idlePixels = renderedPixels()

        check(appState.ttsController.speak(listOf(entry), Locale.US))
        waitForRenderedHighlight()
        val speakingPixels = renderedPixels()
        val diagnostics = seamDiagnostics()

        assertTrue(
            "Read-aloud painted nothing for a message with no stored content tokens. " +
                "Seam state: $diagnostics",
            changedPixelCount(idlePixels, speakingPixels) > 0,
        )
    }

    /**
     * Drives the production lazy-row through every read-aloud transport state
     * and verifies that progress semantics reuse the body bounds without
     * changing the row, bubble, or rendered-message geometry.
     */
    @Test
    fun productionRowKeepsNaturalBoundsAcrossPreparingSpeakingPausedAndIdle() {
        val record = speakableRecord(MESSAGE_A, BODY)
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    parseMarkdown = { plainPaintDocument(BODY) },
                )!!
            }
        renderProductionRow(record)

        val idleBefore = productionBounds(MESSAGE_A, BODY)
        var preparing: ReadAloudBounds? = null
        val started =
            runBlocking {
                appState.ttsController.speakAsync(listOf(entry), Locale.US) {
                    composeRule.waitForIdle()
                    assertTrue(appState.ttsController.state.value is TtsState.Preparing)
                    preparing = productionBounds(MESSAGE_A, BODY)
                    true
                }
            }
        assertTrue(started)
        composeRule.waitForIdle()
        assertTrue(appState.ttsController.state.value is TtsState.Speaking)
        val speaking = productionBounds(MESSAGE_A, BODY)
        val progressBounds =
            composeRule
                .onNodeWithTag(TTS_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(progressBounds.width > 0f)
        assertTrue(progressBounds.height > 0f)
        assertEquals(speaking.body, progressBounds)

        appState.ttsController.pause()
        composeRule.waitForIdle()
        assertTrue(appState.ttsController.state.value is TtsState.Paused)
        val paused = productionBounds(MESSAGE_A, BODY)

        appState.ttsController.resume()
        composeRule.waitForIdle()
        assertTrue(appState.ttsController.state.value is TtsState.Speaking)
        val resumed = productionBounds(MESSAGE_A, BODY)

        appState.ttsController.stop()
        composeRule.waitForIdle()
        assertTrue(appState.ttsController.state.value is TtsState.Idle)
        val idleAfter = productionBounds(MESSAGE_A, BODY)

        assertEquals(idleBefore, checkNotNull(preparing))
        assertEquals(idleBefore, speaking)
        assertEquals(idleBefore, paused)
        assertEquals(idleBefore, resumed)
        assertEquals(idleBefore, idleAfter)
    }

    /** Renders the production timeline row inside the same bounded lazy viewport used by a conversation. */
    private fun renderProductionRow(
        record: AppMessageRecordFfi,
        collapseLongMessages: Boolean = false,
    ) {
        composeRule.setContent {
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .testTag(TTS_VIEWPORT_TAG),
                ) {
                    item(key = item.record.messageIdHex) { row(item, collapseLongMessages = collapseLongMessages) }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Reads the real row, bubble-column, and rendered body bounds from the unmerged semantics tree. */
    private fun productionBounds(
        messageIdHex: String,
        body: String,
    ) = ReadAloudBounds(
        row =
            composeRule
                .onNodeWithTag(messageBubbleRowTestTag(messageIdHex), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot,
        bubble =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(messageIdHex), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot,
        body =
            composeRule
                .onNodeWithText(body, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot,
    )

    /**
     * Waits for the asynchronous Markdown projection to expose its highlight
     * range before the test captures the rendered frame.
     */
    private fun waitForRenderedHighlight() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule
                    .onNodeWithText("bright", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(TtsReadAloudHighlightRangeKey)
            }.getOrNull() != null
        }
    }

    /**
     * Names the first seam that dropped the highlight. The progress tag proves
     * the passage cleared the bubble's message and projection identity gate;
     * the semantics range proves the projection resolver produced a rendered
     * highlight for a leaf.
     */
    private fun seamDiagnostics(): String {
        val passageState = appState.ttsController.state.value
        val progressPresent =
            runCatching {
                composeRule
                    .onNodeWithTag("tts-read-aloud-progress", useUnmergedTree = true)
                    .fetchSemanticsNode()
            }.isSuccess
        val highlightRange =
            runCatching {
                composeRule
                    .onNodeWithText("bright", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(TtsReadAloudHighlightRangeKey)
            }.getOrNull()
        return "controllerState=${passageState::class.simpleName} " +
            "controllerPassage=${(passageState as? TtsState.Speaking)?.passage} " +
            "projectionGatePassed=$progressPresent " +
            "renderedHighlightRange=$highlightRange"
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun row(
        item: TimelineMessage,
        collapseLongMessages: Boolean = false,
    ) {
        val fixture = remember(controller, appState) { PaintTimelineRow(controller, appState, sentenceLayouts) }
        fixture.Render(item, collapseLongMessages)
    }

    private fun richRecord() =
        AppMessageRecordFfi(
            messageIdHex = MESSAGE_B,
            direction = "received",
            groupIdHex = GROUP_ID,
            sender = SENDER_ID,
            plaintext = RICH_BODY,
            contentTokens = richPaintDocument(),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    /**
     * A message the parser gave no blocks for, which is every message stored
     * before content tokens existed. The speakable source then reports
     * `useStoredContentTokens = false`, and the bubble has no edited document
     * to fall back on.
     */
    private fun untokenizedRecord() =
        AppMessageRecordFfi(
            messageIdHex = MESSAGE_D,
            direction = "received",
            groupIdHex = GROUP_ID,
            sender = SENDER_ID,
            plaintext = BODY,
            contentTokens = emptyPaintDocument(),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    /**
     * A frame with one colour in it is a broken capture, not a rendered row:
     * the harness draws text on a bubble, so a real frame always holds several.
     * Native capture can fail transiently under load, and reporting that as
     * "painted nothing" would accuse the production code of this suite's own
     * flakiness. Retry once, then say plainly which one happened.
     */
    private fun renderedPixels(): IntArray {
        repeat(2) { attempt ->
            val pixels = capturedPixels()
            if (pixels.any { it != pixels[0] }) return pixels
            if (attempt == 0) composeRule.waitForIdle()
        }
        throw AssertionError(
            "Capture produced a uniform frame twice, so no rendering was observed at all. " +
                "This is a capture failure, not a missing highlight.",
        )
    }

    private fun capturedPixels(): IntArray {
        val pixelMap = composeRule.onRoot().captureToImage().toPixelMap()
        return IntArray(pixelMap.width * pixelMap.height) { index ->
            pixelMap[index % pixelMap.width, index / pixelMap.width].toArgb()
        }
    }

    private fun changedPixelCount(
        before: IntArray,
        after: IntArray,
    ): Int {
        if (before.size != after.size) return maxOf(before.size, after.size)
        var changed = 0
        before.indices.forEach { index -> if (before[index] != after[index]) changed += 1 }
        return changed
    }

    private fun timelineMessage(record: AppMessageRecordFfi) =
        TimelineMessage(
            id = "msg:${record.messageIdHex}",
            record = record,
            status = MessageStatus.Received,
        )

    private fun speakableRecord(
        messageIdHex: String,
        plaintext: String,
    ) = speakablePaintRecord(messageIdHex, plaintext, GROUP_ID, SENDER_ID)

    /** Captures the three production layout boundaries that read-aloud state must leave unchanged. */
    private data class ReadAloudBounds(
        val row: Rect,
        val bubble: Rect,
        val body: Rect,
    )

    private companion object {
        const val ACCOUNT_REF = "personal"
        const val SENDER_NAME = "Alice"
        const val BODY = "Hello bright world."
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_A = "05" + "00".repeat(31)
        val MESSAGE_B = "06" + "00".repeat(31)
        val MESSAGE_C = "07" + "00".repeat(31)
        val MESSAGE_D = "08" + "00".repeat(31)
        const val LONG_BODY_LINES = 90
        const val TTS_VIEWPORT_TAG = "tts-natural-height-viewport"
        const val TTS_PROGRESS_TAG = "tts-read-aloud-progress"
        const val RICH_BODY =
            "# Release notes\n\nImportant **bright** details with `code` and [a link](https://example.com/docs).\n\n- First item.\n\n> A quoted line."

        // "bright" inside the engine payload, which carries the "Alice: "
        // sender announcement in front of the message body.
        val WORD_START = "$SENDER_NAME: ".length + BODY.indexOf("bright")
        val WORD_END = WORD_START + "bright".length
    }
}
