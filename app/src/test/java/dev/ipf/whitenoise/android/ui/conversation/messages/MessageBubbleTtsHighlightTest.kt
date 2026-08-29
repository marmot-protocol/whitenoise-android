package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.ui.TtsLeafHighlight
import dev.ipf.whitenoise.android.ui.TtsLeafHighlightResolver
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBubbleTtsHighlightTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun plainTextLeafDrawsHighlightForActiveWord() {
        val projection = legacyTextToSpeakableProjection("Hello bright world.")
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 6, 12)),
            )
        val resolver =
            buildTtsLeafHighlightResolver(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                locale = Locale.US,
            ) as TtsLeafHighlightResolver
        var captured: TtsLeafHighlight? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                val highlight = resolver("plain", "Hello bright world.")
                captured = highlight
                HighlightedPlainText(
                    text = "Hello bright world.",
                    highlightRange = highlight,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(0 until 19, captured?.sentence)
        assertEquals(6 until 12, captured?.word)
    }

    @Test
    fun omittedUrlBubbleDrawsSentenceBandAndWordAccent() {
        val rendered = "Check https://example.com/page now."
        val wordStart = rendered.indexOf("now")
        val projection = legacyTextToSpeakableProjection(rendered)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", wordStart, wordStart + 3)),
            )
        val resolver =
            buildTtsLeafHighlightResolver(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                locale = Locale.US,
            ) as TtsLeafHighlightResolver
        var captured: TtsLeafHighlight? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                val highlight = resolver("plain", rendered)
                captured = highlight
                HighlightedPlainText(
                    text = rendered,
                    highlightRange = highlight,
                    testTag = "url-highlighted-text",
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(2, captured?.sentenceRanges?.size)
        assertEquals(wordStart until wordStart + 3, captured?.word)
        val semantics = composeRule.onNodeWithTag("url-highlighted-text").fetchSemanticsNode().config
        assertEquals(
            wordStart until wordStart + 3,
            semantics.getOrNull(TtsReadAloudHighlightRangeKey),
        )
        assertEquals(
            0 until rendered.length,
            semantics.getOrNull(TtsReadAloudSentenceHighlightRangeKey),
        )
    }

    @Test
    fun visibleWordUpdatesRecomposeHighlightWithoutRelayout() {
        val projection = legacyTextToSpeakableProjection("Hello bright world.")
        var passage by mutableStateOf(
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 6, 12)),
            ),
        )
        composeRule.setContent {
            WhiteNoiseTheme {
                val resolver =
                    rememberTtsLeafHighlightResolver(
                        passage = passage,
                        messageIdHex = "m1",
                        projection = projection,
                        locale = Locale.US,
                    ) as TtsLeafHighlightResolver
                HighlightedPlainText(
                    text = "Hello bright world.",
                    highlightRange = resolver("plain", "Hello bright world."),
                    testTag = "highlighted-text",
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hello bright world.").assertExists()

        passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 13, 18)),
            )
        composeRule.waitForIdle()

        val semantics = composeRule.onNodeWithTag("highlighted-text").fetchSemanticsNode().config
        assertEquals(
            13 until 18,
            semantics.getOrNull(TtsReadAloudHighlightRangeKey),
        )
    }

    @Test
    fun selectionModeSuppressesHighlightResolver() {
        val projection = legacyTextToSpeakableProjection("Hello world.")
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 0, 5)),
            )
        val resolver =
            buildTtsLeafHighlightResolver(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                locale = Locale.US,
            )
        assertNull(
            activeTtsLeafHighlightResolver(
                resolver = resolver,
                textSelectionMode = true,
                suppressForCollapsed = false,
            )?.invoke("plain", "Hello world."),
        )
    }

    @Test
    fun unmeasuredCollapseEnabledBodySuppressesHighlightUntilMeasured() {
        assertEquals(
            true,
            ttsBodyIsCollapsed(
                collapseEnabled = true,
                measuredBodyHeightPx = null,
                maxBodyHeightPx = 400,
            ),
        )
    }

    @Test
    fun shortMessageInCollapseEnabledTimelineKeepsHighlightResolver() {
        assertEquals(
            false,
            ttsBodyIsCollapsed(
                collapseEnabled = true,
                measuredBodyHeightPx = 40,
                maxBodyHeightPx = 400,
            ),
        )
    }

    @Test
    fun bodyBeyondCollapseLimitSuppressesHighlightResolver() {
        assertEquals(
            true,
            ttsBodyIsCollapsed(
                collapseEnabled = true,
                measuredBodyHeightPx = 401,
                maxBodyHeightPx = 400,
            ),
        )
    }

    @Test
    fun collapsedLongMessageSuppressesHighlightResolver() {
        val projection = legacyTextToSpeakableProjection("Hello world.")
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 0, 5)),
            )
        val resolver =
            buildTtsLeafHighlightResolver(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                locale = Locale.US,
            )
        assertNull(
            activeTtsLeafHighlightResolver(
                resolver = resolver,
                textSelectionMode = false,
                suppressForCollapsed = true,
            )?.invoke("plain", "Hello world."),
        )
    }

    @Test
    fun readAloudProgressPreservesMessageTextAndAnnouncesSentenceChanges() {
        val progress =
            TtsReadAloudProgress(
                sentenceIndex = 1,
                sentenceCount = 3,
                messageIndex = 0,
                messageCount = 2,
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                readAloudMessageSemantics(progress = progress) {
                    Text(text = "Hello bright world.")
                }
            }
        }

        composeRule.onNodeWithText("Hello bright world.").assertExists()
        composeRule
            .onNodeWithTag("tts-read-aloud-progress")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(
                        app.getString(
                            R.string.tts_bar_progress,
                            2,
                            3,
                            1,
                            2,
                        ),
                    ),
                ),
            )
    }

    @Test
    fun staleMessageIdDoesNotResolveHighlight() {
        val projection = legacyTextToSpeakableProjection("Hello world.")
        val passage =
            TtsPassage(
                messageIdHex = "other",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 0, 5)),
            )
        val resolver =
            buildTtsLeafHighlightResolver(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                locale = Locale.US,
            )
        assertNull(resolver?.invoke("plain", "Hello world."))
    }
}

@Composable
private fun HighlightedPlainText(
    text: String,
    highlightRange: TtsLeafHighlight?,
    testTag: String? = null,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val style =
        rememberTtsReadAloudHighlightStyle(
            background = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            sentenceAccent = MaterialTheme.colorScheme.outlineVariant,
            wordAccent = MaterialTheme.colorScheme.tertiary,
            amoled = false,
        )
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text = text,
            modifier =
                Modifier
                    .width(280.dp)
                    .padding(12.dp)
                    .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                    .ttsReadAloudHighlight(layout, highlightRange, style),
            onTextLayout = { layout = it },
        )
    }
}
