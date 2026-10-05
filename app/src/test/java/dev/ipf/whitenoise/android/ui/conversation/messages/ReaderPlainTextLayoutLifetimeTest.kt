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

    /** A stable leaf ID still retires the old text's speech geometry and the final text on disposal. */
    @Test
    fun changingTextWithStableLeafClearsBothLayoutLifetimes() {
        val text = mutableStateOf("Original sentence.")
        val mounted = mutableStateOf(true)
        val reported = mutableSetOf<String>()
        val cleared = mutableListOf<String>()
        val reporter: TtsSentenceLayoutReporter = { _, rendered, layout, _ ->
            if (layout == null) cleared += rendered else reported += rendered
        }
        composeRule.setContent {
            WhiteNoiseTheme {
                if (mounted.value) {
                    ReaderSelectablePlainText(
                        text = text.value,
                        onSelectableTextLayoutChanged = { _, _, _ -> },
                        leafId = "plain",
                        sentenceLayoutReporter = reporter,
                    )
                }
            }
        }
        composeRule.runOnIdle {
            assertTrue("Original sentence." in reported)
            text.value = "Replacement sentence."
        }
        composeRule.runOnIdle {
            assertEquals(listOf("Original sentence."), cleared)
            assertTrue("Replacement sentence." in reported)
            mounted.value = false
        }
        composeRule.runOnIdle {
            assertEquals(listOf("Original sentence.", "Replacement sentence."), cleared)
        }
    }

    /** Sentence progression preserves the independent native-selection registration. */
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

    /** Markdown re-reports speech geometry without unregistering hit testing. */
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
