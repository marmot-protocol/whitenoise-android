package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.SessionHarness
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.ui.conversation.media.TEXT_ATTACHMENT_READER_BODY_TAG
import dev.ipf.whitenoise.android.ui.conversation.media.TEXT_ATTACHMENT_READER_TAG
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentCandidate
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentFormat
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentPreview
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentReaderScreen
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentReaderState
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentSpeechOwner
import dev.ipf.whitenoise.android.ui.conversation.media.textAttachmentTtsEntry
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudHighlightRangeKey
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudSentenceHighlightRangeKey
import dev.ipf.whitenoise.android.ui.conversation.messages.preparedHitFromRenderedHit
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TextAttachmentReadAloudScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val harness = SessionHarness(TestScope())
    private var showReader by mutableStateOf(true)
    private var sourceCurrent by mutableStateOf(true)
    private val speechOwner = TextAttachmentSpeechOwner(harness.controller, { sourceCurrent }, { true })

    /** Pins active Markdown sentence and word highlighting in the light theme. */
    @Test
    fun markdownActiveSentenceAndWordLight() {
        render(markdownPreview)
        assertHighlights("First sentence. Second sentence.")
        capture("text_attachment_read_aloud_markdown_light.png")
    }

    /** Pins active Markdown highlighting against AMOLED surfaces. */
    @Test
    fun markdownActiveSentenceAndWordAmoled() {
        render(markdownPreview, amoled = true)
        assertHighlights("First sentence. Second sentence.")
        capture("text_attachment_read_aloud_markdown_amoled.png")
    }

    /** Pins active plain-text highlighting at large font scale with RTL chrome. */
    @Test
    fun plainTextActiveSentenceLargeRtl() {
        render(plainPreview, rtl = true, fontScale = 1.6f)
        assertHighlights(plainPreview.text)
        capture("text_attachment_read_aloud_plain_large_rtl.png")
    }

    /** Body seeking preserves the queue; the toolbar explicitly starts at the document top. */
    @Test
    fun plainDoubleTapSeeksTheExistingSessionAndToolbarStartsAtTop() {
        render(plainPreview)
        val session = harness.controller.state.value.sessionId
        doubleTap("Second")
        assertEquals(1, harness.controller.state.value.sentenceIndexWithinMessage)
        assertEquals(session, harness.controller.state.value.sessionId)
        composeRule.onNodeWithContentDescription(string(R.string.read_aloud)).performClick()
        assertEquals(0, harness.controller.state.value.sentenceIndexWithinMessage)
        assertTrue(harness.controller.state.value.sessionId != session)
    }

    /** Markdown hits use rendered leaf coordinates rather than raw markup offsets. */
    @Test
    fun markdownDoubleTapUsesRenderedSentenceCoordinates() {
        render(markdownPreview)
        val session = harness.controller.state.value.sessionId
        doubleTap("Second")
        assertEquals(1, harness.controller.state.value.sentenceIndexWithinMessage)
        assertEquals(session, harness.controller.state.value.sessionId)
    }

    /** An explicit inactive-reader tap starts at its mapped sentence, never a guessed top fallback. */
    @Test
    fun inactiveDoubleTapStartsAtTheTappedSentenceInsteadOfTheTop() {
        render(plainPreview)
        composeRule.runOnIdle { harness.controller.stop() }
        doubleTap("Second")
        assertEquals(1, harness.controller.state.value.sentenceIndexWithinMessage)
    }

    /** Returning to a paused reader restores highlighting without another speech submission. */
    @Test
    fun closeAndReturnRestoresPausedHighlightWithoutRestartingSpeech() {
        render(markdownPreview)
        composeRule.runOnIdle {
            harness.controller.pause()
            showReader = false
        }
        val session = harness.controller.state.value.sessionId
        val spoken = harness.spokenTexts().size
        composeRule.runOnIdle { showReader = true }
        assertHighlights("First sentence. Second sentence.")
        assertEquals(session, harness.controller.state.value.sessionId)
        assertEquals(spoken, harness.spokenTexts().size)
        capture("text_attachment_read_aloud_paused_return.png")
    }

    /** A revoked source cannot control speech through stale gestures. */
    @Test
    fun revokedSourceDoubleTapCannotSeekOrRestart() {
        render(plainPreview)
        val spoken = harness.spokenTexts().size
        composeRule.runOnIdle { sourceCurrent = false }
        doubleTap("Second")
        assertEquals(spoken, harness.spokenTexts().size)
    }

    /** Text selection removes competing highlights but leaves intentional shared playback running. */
    @Test
    fun selectionSuppressesHighlightWithoutStoppingSharedSpeech() {
        render(plainPreview)
        val session = harness.controller.state.value.sessionId
        composeRule.onNodeWithText(plainPreview.text).performTouchInput { longClick() }
        composeRule
            .onNodeWithText(plainPreview.text, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(TtsReadAloudSentenceHighlightRangeKey))
        assertEquals(session, harness.controller.state.value.sessionId)
    }

    /** Direct dragging suspends follow for the current passage; later progression reveals the next sentence. */
    @Test
    fun manualDragKeepsBrowsingUntilExplicitResume() {
        val longPreview = plainPreview.copy(text = (1..60).joinToString("\n\n") { "Sentence number $it continues." })
        render(longPreview)
        composeRule
            .onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(TEXT_ATTACHMENT_READER_BODY_TAG)))
            .performTouchInput { swipeUp() }
        composeRule.onNodeWithContentDescription(string(R.string.tts_resume_follow)).assertIsDisplayed()
        composeRule.runOnIdle { harness.engine.complete(0) }
        composeRule.onNodeWithContentDescription(string(R.string.tts_resume_follow)).assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription(string(R.string.tts_resume_follow)).assertDoesNotExist()
    }

    /** Starts fixture speech and mounts the reader with theme, direction and font-scale overrides. */
    private fun render(
        preview: TextAttachmentPreview,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val entry = textAttachmentTtsEntry(preview, "alice", "Alice", "message", 0)
        harness.speakEntries(listOf(entry))
        composeRule.setContent {
            val state by harness.controller.state.collectAsState()
            val leaf = if (preview.markdownDocument == null) "plain" else "b0/n0/n0"
            val wordState =
                when (val current = state) {
                    is TtsState.Speaking ->
                        current.copy(
                            passage = current.passage?.copy(visibleWord = listOf(TtsVisibleTextSpan(leaf, 0, 5))),
                        )
                    else -> state
                }
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = amoled, amoled = amoled, fontScale = fontScale) {
                    if (showReader) readerSurface(preview, entry, wordState)
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Mounts the production screen against the fixture's live projection and source ownership. */
    @Composable
    private fun readerSurface(
        preview: TextAttachmentPreview,
        entry: TtsSpeakableEntry,
        state: TtsState,
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            TextAttachmentReaderScreen(
                candidate = preview.candidate,
                state = TextAttachmentReaderState.Ready(preview),
                onDismiss = { showReader = false },
                onRetry = {},
                onCopy = {},
                onReadAloud = {},
                onOpenExternal = {},
                isReading = speechOwner.ownsAttachment(state, "message", 0),
                onReadFromTop = { harness.speakEntries(listOf(entry)) },
                playback =
                    speechOwner.playback(entry, state) { hit ->
                        runBlocking {
                            harness.controller.speakAsync(
                                listOf(entry),
                                Locale.US,
                                startRenderedHit = preparedHitFromRenderedHit(entry, hit),
                                isCurrent = { sourceCurrent },
                            ) { true }
                        }
                    },
            )
        }
    }

    /** Requires a sentence highlight and, while speaking, the fixture's current word highlight. */
    private fun assertHighlights(text: String) {
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(TtsReadAloudSentenceHighlightRangeKey))
        if (harness.controller.state.value is TtsState.Speaking) {
            composeRule
                .onNodeWithText(text, useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(TtsReadAloudHighlightRangeKey, 0 until 5))
        }
    }

    /** Sends two pointer taps to measured rendered word bounds. */
    private fun doubleTap(word: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = composeRule.onNodeWithText(word, substring = true, useUnmergedTree = true)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val position =
            layout
                .getBoundingBox(
                    layout.layoutInput.text.text
                        .indexOf(word) + 2,
                ).center
        node.performTouchInput {
            down(position)
            up()
            advanceEventTime((viewConfiguration.doubleTapMinTimeMillis + viewConfiguration.doubleTapTimeoutMillis) / 2)
            down(position)
            up()
        }
        composeRule.waitForIdle()
    }

    /** Captures the reader root into its owned golden. */
    private fun capture(name: String) {
        composeRule.onNodeWithTag(TEXT_ATTACHMENT_READER_TAG).captureRoboImage("src/test/snapshots/$name")
    }

    /** Resolves production accessible labels in the fixture locale. */
    private fun string(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private companion object {
        val plainPreview =
            TextAttachmentPreview(
                TextAttachmentCandidate("notes.txt", "text/plain", TextAttachmentFormat.PlainText),
                "First sentence. Second sentence.",
            )
        val markdownPreview =
            plainPreview.copy(
                candidate = TextAttachmentCandidate("notes.md", "text/markdown", TextAttachmentFormat.Markdown),
                text = "**First sentence.** Second sentence.",
                markdownDocument =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks =
                            listOf(
                                MarkdownBlockFfi.Paragraph(
                                    listOf(
                                        MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("First sentence."))),
                                        MarkdownInlineFfi.Text(" Second sentence."),
                                    ),
                                ),
                            ),
                        blankLinesBefore = ByteArray(0),
                    ),
            )
    }
}
