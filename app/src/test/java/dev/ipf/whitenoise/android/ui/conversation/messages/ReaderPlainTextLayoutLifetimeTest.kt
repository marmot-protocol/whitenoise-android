package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.TtsSentenceLayoutReporter
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderPlainTextLayoutLifetimeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun changingSentenceReporterDoesNotRemoveTheSelectionLayout() {
        val sentence = mutableStateOf(0)
        var removals = 0
        var reports = 0
        val reportedSentences = mutableSetOf<Int>()
        val selectionReporter: (Any, TextLayoutResult?, LayoutCoordinates?) -> Unit = { _, layout, _ ->
            if (layout == null) removals++ else reports++
        }
        composeRule.setContent {
            val target = sentence.value
            val reporter: TtsSentenceLayoutReporter =
                remember(target) { { _, _, _, _ -> reportedSentences += target } }
            WhiteNoiseTheme {
                ReaderSelectablePlainText(
                    text = "First sentence. Second sentence.",
                    onSelectableTextLayoutChanged = selectionReporter,
                    leafId = "plain",
                    sentenceLayoutReporter = reporter,
                )
            }
        }
        composeRule.runOnIdle {
            assertTrue(reports > 0)
            sentence.value = 1
        }
        composeRule.runOnIdle {
            assertEquals(setOf(0, 1), reportedSentences)
            assertEquals(0, removals)
        }
    }

    @Test
    fun markdownSentenceReporterChangesKeepSelectionHitTestingRegistered() {
        val sentence = mutableStateOf(0)
        var removals = 0
        var reports = 0
        val reportedSentences = mutableSetOf<Int>()
        val selectionReporter: (Any, TextLayoutResult?, LayoutCoordinates?) -> Unit = { _, layout, _ ->
            if (layout == null) removals++ else reports++
        }
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("First. Second.")))),
                blankLinesBefore = ByteArray(0),
            )
        composeRule.setContent {
            val target = sentence.value
            val reporter: TtsSentenceLayoutReporter =
                remember(target) { { _, _, _, _ -> reportedSentences += target } }
            WhiteNoiseTheme {
                MarkdownMessageBody(
                    document = document,
                    onSelectableTextLayoutChanged = selectionReporter,
                    ttsSentenceLayoutReporter = reporter,
                )
            }
        }
        composeRule.runOnIdle {
            assertTrue(reports > 0)
            sentence.value = 1
        }
        composeRule.runOnIdle {
            assertEquals(setOf(0, 1), reportedSentences)
            assertEquals(0, removals)
        }
    }
}
