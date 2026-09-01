package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Direct layout coverage for the exact character-cell highlight boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsHighlightBoundingBoxesTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A range beginning on a new line starts at that character, never the prior caret affinity. */
    @Test
    fun rangeAtLineStartUsesOnlyTheOwningLineCharacterCells() {
        val text = "Previous line.\nSecond sentence."
        val layout = measure(text)
        val start = text.indexOf("Second")
        val startCell = layout.getBoundingBox(start)

        val boxes = highlightBoundingBoxes(layout, TextRange(start, text.length))

        assertTrue(boxes.isNotEmpty())
        assertEquals(startCell.left, boxes.first().bounds.left, FLOAT_TOLERANCE)
        assertEquals(startCell.top, boxes.first().bounds.top, FLOAT_TOLERANCE)
        assertTrue(boxes.none { it.bounds.top < startCell.top })
    }

    /** Measures deterministic monospace text through the same Compose layout implementation as production. */
    private fun measure(text: String): TextLayoutResult {
        lateinit var result: TextLayoutResult
        composeRule.setContent {
            Text(
                text = text,
                modifier = Modifier.width(220.dp),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                onTextLayout = { result = it },
            )
        }
        composeRule.waitForIdle()
        return result
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.01f
    }
}
