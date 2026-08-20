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
import dev.ipf.marmotkit.MarkdownTableCellFfi
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.TtsLeafHighlightResolver
import dev.ipf.whitenoise.android.ui.conversation.messages.buildTtsLeafHighlightResolver
import dev.ipf.whitenoise.android.ui.conversation.messages.ttsReadAloudHighlight
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableProjection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
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
    fun mixedMarkdownNestedListWordHighlightDark() {
        renderMixedMarkdown(mine = false, darkTheme = true, amoled = false)
        capture("message_bubble_tts_mixed_markdown_nested_list_word_dark")
    }

    @Test
    fun editedMarkdownIncomingWordHighlightLight() {
        renderEditedMarkdown(mine = false, darkTheme = false, amoled = false)
        capture("message_bubble_tts_edited_markdown_incoming_word_light")
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
                BubbleFixture(mine = mine, tag = TAG) {
                    HighlightedPlainLeaf(text = "Hello bright world.", resolver = resolver)
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
        renderMarkdownDocument(document, passage, mine, darkTheme, amoled, largeFont)
    }

    private fun renderMixedMarkdown(
        mine: Boolean,
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        val document = mixedMarkdownDocument()
        val projection = markdownDocumentToSpeakableProjection(document)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 3,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b2/i0/b1/i0/b0/n0", 0, 6)),
            )
        renderMarkdownDocument(document, passage, mine, darkTheme, amoled, largeFont = false)
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
        renderMarkdownDocument(document, passage, mine, darkTheme, amoled, largeFont = false)
    }

    private fun renderMarkdownDocument(
        document: MarkdownDocumentFfi,
        passage: TtsPassage,
        mine: Boolean,
        darkTheme: Boolean,
        amoled: Boolean,
        largeFont: Boolean,
    ) {
        val projection = markdownDocumentToSpeakableProjection(document)
        val resolver = buildTtsLeafHighlightResolver(passage, "m1", projection, Locale.US)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = if (largeFont) 1.3f else 1f) {
                BubbleFixture(mine = mine, tag = TAG) {
                    MarkdownMessageBody(
                        document,
                        ttsLeafHighlightResolver = resolver,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "message-bubble-tts-highlight"
    }
}

private fun mixedMarkdownDocument(): MarkdownDocumentFfi =
    MarkdownDocumentFfi(
        truncated = false,
        blankLinesBefore = byteArrayOf(),
        blocks =
            listOf(
                screenshotParagraph("Intro line."),
                MarkdownBlockFfi.BlockQuote(
                    blocks = listOf(screenshotParagraph("Quoted line.")),
                    blankLinesBefore = byteArrayOf(),
                ),
                MarkdownBlockFfi.ListBlock(
                    kind = MarkdownListKindFfi.Ordered(start = 1u, delimiter = "."),
                    tight = true,
                    items =
                        listOf(
                            MarkdownListItemFfi(
                                blocks =
                                    listOf(
                                        screenshotParagraph("Parent row"),
                                        MarkdownBlockFfi.ListBlock(
                                            kind = MarkdownListKindFfi.Bullet(marker = "-"),
                                            tight = true,
                                            items =
                                                listOf(
                                                    MarkdownListItemFfi(
                                                        blocks = listOf(screenshotParagraph("Nested row")),
                                                        checked = null,
                                                        blankLinesBefore = byteArrayOf(),
                                                    ),
                                                ),
                                        ),
                                    ),
                                checked = null,
                                blankLinesBefore = byteArrayOf(),
                            ),
                            MarkdownListItemFfi(
                                blocks = listOf(screenshotParagraph("Sibling row")),
                                checked = null,
                                blankLinesBefore = byteArrayOf(),
                            ),
                        ),
                ),
                MarkdownBlockFfi.Table(
                    alignments = emptyList(),
                    header =
                        listOf(
                            MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("Name"))),
                            MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("Value"))),
                        ),
                    rows =
                        listOf(
                            listOf(
                                MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("Latency"))),
                                MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("Low"))),
                            ),
                        ),
                ),
            ),
    )

private fun screenshotParagraph(text: String): MarkdownBlockFfi.Paragraph =
    MarkdownBlockFfi.Paragraph(
        listOf(MarkdownInlineFfi.Text(text)),
    )

@Composable
private fun BubbleFixture(
    mine: Boolean,
    tag: String,
    content: @Composable () -> Unit,
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
                        content()
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
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val highlight = resolver?.invoke("plain", text)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.ttsReadAloudHighlight(layout, highlight),
        onTextLayout = { layout = it },
    )
}
