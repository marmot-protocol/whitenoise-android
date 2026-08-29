package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.TtsLeafHighlightResolver
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudHighlightStyle
import dev.ipf.whitenoise.android.ui.conversation.messages.buildTtsLeafHighlightResolver
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberTtsReadAloudHighlightStyle
import dev.ipf.whitenoise.android.ui.conversation.messages.ttsReadAloudHighlight
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableProjection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
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
class MessageBubbleTtsHighlightScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun plainIncomingWordHighlightLight() {
        renderPlain(mine = false, darkTheme = false, amoled = false)
        capture("message_bubble_tts_plain_incoming_word_light")
    }

    @Test
    fun plainOutgoingWordHighlightDark() {
        renderPlain(mine = true, darkTheme = true, amoled = false)
        capture("message_bubble_tts_plain_outgoing_word_dark")
    }

    @Test
    fun rangeSilentEstimatedWordHighlightLight() {
        renderEstimatedWord()
        capture("message_bubble_tts_range_silent_estimated_word_light")
    }

    @Test
    fun plainIncomingOmittedUrlSentenceBandLight() {
        renderOmittedUrl(mine = false, darkTheme = false, amoled = false)
        capture("message_bubble_tts_omitted_url_incoming_light")
    }

    @Test
    fun plainOutgoingOmittedUrlSentenceBandAmoled() {
        renderOmittedUrl(mine = true, darkTheme = true, amoled = true)
        capture("message_bubble_tts_omitted_url_outgoing_amoled")
    }

    @Test
    fun markdownIncomingSentenceFallbackAmoled() {
        renderMarkdown(mine = false, darkTheme = true, amoled = true, sentenceFallback = true)
        capture("message_bubble_tts_markdown_incoming_sentence_amoled")
    }

    @Test
    fun markdownOutgoingWordHighlightLargeFont() {
        renderMarkdown(mine = true, darkTheme = false, amoled = false, sentenceFallback = false, largeFont = true)
        capture("message_bubble_tts_markdown_outgoing_word_large_font")
    }

    @Test
    fun editedMarkdownIncomingWordHighlightLight() {
        renderEditedMarkdown(mine = false, darkTheme = false, amoled = false)
        capture("message_bubble_tts_edited_markdown_incoming_word_light")
    }

    @Test
    fun nestedListIncomingWordHighlightLight() {
        renderNestedList(mine = false, darkTheme = false, amoled = false, largeFont = false)
        capture("message_bubble_tts_nested_list_incoming_word_light")
    }

    @Test
    fun nestedListOutgoingWordHighlightAmoledLargeFont() {
        renderNestedList(mine = true, darkTheme = true, amoled = true, largeFont = true)
        capture("message_bubble_tts_nested_list_outgoing_word_amoled_large_font")
    }

    private fun capture(name: String) {
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun renderPlain(
        mine: Boolean,
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        val projection = legacyTextToSpeakableProjection("Hello bright world.")
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 6, 12)),
            )
        val resolver = buildTtsLeafHighlightResolver(passage, "m1", projection, Locale.US)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                BubbleFixture(mine = mine, tag = TAG) { style ->
                    HighlightedPlainLeaf(text = "Hello bright world.", resolver = resolver, style = style)
                }
            }
        }
    }

    /** Visual contract for the passage emitted by the range-silent timing lane. */
    private fun renderEstimatedWord() {
        val text = "Estimated timing follows speech."
        val wordStart = text.indexOf("timing")
        val projection = legacyTextToSpeakableProjection(text)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", wordStart, wordStart + "timing".length)),
            )
        val resolver = buildTtsLeafHighlightResolver(passage, "m1", projection, Locale.US)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, amoled = false) {
                BubbleFixture(mine = false, tag = TAG) { style ->
                    HighlightedPlainLeaf(text = text, resolver = resolver, style = style)
                }
            }
        }
    }

    // The URL is rendered but never spoken, so the sentence band covers the
    // two rendered pieces around it and leaves the link itself unpainted.
    private fun renderOmittedUrl(
        mine: Boolean,
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        val text = "Check https://example.com/page now."
        val wordStart = text.indexOf("now")
        val projection = legacyTextToSpeakableProjection(text)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", wordStart, wordStart + 3)),
            )
        val resolver = buildTtsLeafHighlightResolver(passage, "m1", projection, Locale.US)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                BubbleFixture(mine = mine, tag = TAG) { style ->
                    HighlightedPlainLeaf(text = text, resolver = resolver, style = style)
                }
            }
        }
    }

    private fun renderMarkdown(
        mine: Boolean,
        darkTheme: Boolean,
        amoled: Boolean,
        sentenceFallback: Boolean,
        largeFont: Boolean = false,
    ) {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            inlines =
                                listOf(
                                    MarkdownInlineFfi.Text("Read "),
                                    MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("aloud"))),
                                    MarkdownInlineFfi.Text(" now."),
                                ),
                        ),
                    ),
            )
        val projection = markdownDocumentToSpeakableProjection(document)
        val passage =
            if (sentenceFallback) {
                TtsPassage("m1", sentenceIndex = 0, projectionId = projection.projectionId)
            } else {
                TtsPassage(
                    messageIdHex = "m1",
                    sentenceIndex = 0,
                    projectionId = projection.projectionId,
                    visibleWord = listOf(TtsVisibleTextSpan("b0/n1/n0", 0, 5)),
                )
            }
        val resolver = buildTtsLeafHighlightResolver(passage, "m1", projection, Locale.US)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = if (largeFont) 1.3f else 1f) {
                BubbleFixture(mine = mine, tag = TAG) { style ->
                    MarkdownMessageBody(
                        document,
                        ttsLeafHighlightResolver = resolver,
                        ttsReadAloudHighlightStyle = style,
                    )
                }
            }
        }
    }

    private fun renderEditedMarkdown(
        mine: Boolean,
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            inlines =
                                listOf(
                                    MarkdownInlineFfi.Text("Edited "),
                                    MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("value"))),
                                ),
                        ),
                    ),
            )
        val projection = markdownDocumentToSpeakableProjection(document)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b0/n1/n0", 0, 5)),
            )
        val resolver = buildTtsLeafHighlightResolver(passage, "m1", projection, Locale.US)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                BubbleFixture(mine = mine, tag = TAG) { style ->
                    MarkdownMessageBody(
                        document,
                        ttsLeafHighlightResolver = resolver,
                        ttsReadAloudHighlightStyle = style,
                    )
                }
            }
        }
    }

    private fun renderNestedList(
        mine: Boolean,
        darkTheme: Boolean,
        amoled: Boolean,
        largeFont: Boolean,
    ) {
        fun paragraph(vararg inlines: MarkdownInlineFfi) = MarkdownBlockFfi.Paragraph(inlines.toList())

        fun item(vararg blocks: MarkdownBlockFfi) =
            MarkdownListItemFfi(
                blocks = blocks.toList(),
                checked = null,
                blankLinesBefore = byteArrayOf(),
            )
        val nestedList =
            MarkdownBlockFfi.ListBlock(
                kind = MarkdownListKindFfi.Bullet("-"),
                tight = true,
                items =
                    listOf(
                        item(
                            paragraph(
                                MarkdownInlineFfi.Text("Nested "),
                                MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("word"))),
                                MarkdownInlineFfi.Text(" active"),
                            ),
                        ),
                    ),
            )
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.ListBlock(
                            kind = MarkdownListKindFfi.Bullet("-"),
                            tight = true,
                            items =
                                listOf(
                                    item(paragraph(MarkdownInlineFfi.Text("Outer row")), nestedList),
                                    item(paragraph(MarkdownInlineFfi.Text("Sibling row"))),
                                ),
                        ),
                    ),
            )
        val projection = markdownDocumentToSpeakableProjection(document)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 1,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b0/i0/b1/i0/b0/n1/n0", 0, 4)),
            )
        val resolver = buildTtsLeafHighlightResolver(passage, "m1", projection, Locale.US)
        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = darkTheme,
                amoled = amoled,
                fontScale = if (largeFont) 1.3f else 1f,
            ) {
                BubbleFixture(mine = mine, tag = TAG) { style ->
                    MarkdownMessageBody(
                        document,
                        ttsLeafHighlightResolver = resolver,
                        ttsReadAloudHighlightStyle = style,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "message-bubble-tts-highlight"
    }
}

@Composable
private fun BubbleFixture(
    mine: Boolean,
    tag: String,
    content: @Composable (TtsReadAloudHighlightStyle) -> Unit,
) {
    val bubbleColor =
        if (mine) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val pageColor =
        if (MaterialTheme.colorScheme.background == Color.Black) {
            Color.Black
        } else {
            MaterialTheme.colorScheme.background
        }
    val bubbleContent =
        if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val highlightStyle =
        rememberTtsReadAloudHighlightStyle(
            background = bubbleColor,
            content = bubbleContent,
            sentenceAccent = MaterialTheme.colorScheme.outlineVariant,
            wordAccent = MaterialTheme.colorScheme.tertiary,
            amoled = isAmoledSurfaceTheme(),
        )
    Surface(color = pageColor) {
        Column(
            modifier = Modifier.width(360.dp).padding(16.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            ) {
                Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.width(260.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        content(highlightStyle)
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightedPlainLeaf(
    text: String,
    resolver: TtsLeafHighlightResolver?,
    style: TtsReadAloudHighlightStyle,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val highlight = resolver?.invoke("plain", text)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.ttsReadAloudHighlight(layout, highlight, style),
        onTextLayout = { layout = it },
    )
}
