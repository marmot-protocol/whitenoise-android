package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.ResolvedTextDirection
import dev.ipf.whitenoise.android.state.BLUE_FREE_LIGHT_TEXT_ARGB
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.WCAG_NON_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.state.readableTextArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsReadAloudHighlightTest {
    @Test
    fun halfOpenIntRangeConvertsToExclusiveTextRangeEnd() {
        val textRange = ttsHighlightTextRange(6 until 12, textLength = 20)

        assertEquals(TextRange(6, 12), textRange)
    }

    @Test
    fun singleCharacterHighlightUsesNonCollapsedTextRange() {
        val textRange = ttsHighlightTextRange(3 until 4, textLength = 10)

        assertEquals(TextRange(3, 4), textRange)
        assertTrue(!textRange.collapsed)
    }

    @Test
    fun supplementaryUnicodeHighlightRespectsUtf16CodeUnitBoundaries() {
        val emoji = "a\uD83D\uDE00b"
        val textRange = ttsHighlightTextRange(1 until 3, textLength = emoji.length)

        assertEquals(TextRange(1, 3), textRange)
    }

    @Test
    fun highlightRangeClampsToTextLength() {
        val textRange = ttsHighlightTextRange(8 until 20, textLength = 12)

        assertEquals(TextRange(8, 12), textRange)
    }

    @Test
    fun sentenceMarkerUsesTheLogicalStartEdge() {
        val box = Rect(left = 10f, top = 2f, right = 50f, bottom = 22f)

        assertEquals(10f, ttsSentenceMarkerLeft(box, 2f, ResolvedTextDirection.Ltr))
        assertEquals(48f, ttsSentenceMarkerLeft(box, 2f, ResolvedTextDirection.Rtl))
    }

    @Test
    fun sentenceMarkerUsesParagraphDirectionWhenFirstRunDiffers() {
        val boxes =
            listOf(
                // An RTL paragraph can begin with an LTR token. Both boxes
                // therefore carry the paragraph direction, not their bidi run.
                TtsHighlightBox(Rect(10f, 0f, 30f, 20f), ResolvedTextDirection.Rtl),
                TtsHighlightBox(Rect(35f, 0f, 55f, 20f), ResolvedTextDirection.Rtl),
                TtsHighlightBox(Rect(40f, 20f, 60f, 40f), ResolvedTextDirection.Rtl),
                TtsHighlightBox(Rect(65f, 20f, 85f, 40f), ResolvedTextDirection.Rtl),
            )

        assertEquals(listOf(boxes[1], boxes[3]), ttsSentenceMarkerBoxes(boxes))
    }

    /** Merges touching cells without crossing a line, direction, or visual gap. */
    @Test
    fun adjacentHighlightCellsMergeOnlyInsideOneVisualRun() {
        val boxes =
            listOf(
                TtsHighlightBox(Rect(0f, 0f, 10f, 20f), ResolvedTextDirection.Ltr),
                TtsHighlightBox(Rect(10.25f, 0f, 20f, 20f), ResolvedTextDirection.Ltr),
                TtsHighlightBox(Rect(24f, 0f, 30f, 20f), ResolvedTextDirection.Ltr),
                TtsHighlightBox(Rect(30f, 0f, 40f, 20f), ResolvedTextDirection.Rtl),
                TtsHighlightBox(Rect(0f, 20f, 10f, 40f), ResolvedTextDirection.Ltr),
            )

        assertEquals(
            listOf(
                TtsHighlightBox(Rect(0f, 0f, 20f, 20f), ResolvedTextDirection.Ltr),
                boxes[2],
                boxes[3],
                boxes[4],
            ),
            boxes.mergeAdjacentHighlightBoxes(),
        )
    }

    @Test
    fun lockedThemeMatrixKeepsTextAndMarkersReadable() {
        val cases =
            listOf(
                Triple(0xFFDBE4E5L, 0xFF3F4849L, false),
                Triple(0xFF06B6D4L, 0xFF001F28L, false),
                Triple(0xFF3F4849L, 0xFFBEC8C9L, false),
                Triple(0xFF000000L, 0xFFB0A000L, true),
            )

        cases.forEach { (background, content, amoled) ->
            val style =
                resolveTtsReadAloudHighlightStyle(
                    background = background,
                    content = content,
                    sentenceAccent = if (amoled) 0xFF665A00L else content,
                    wordAccent = if (amoled) BLUE_FREE_LIGHT_TEXT_ARGB else 0xFF00696EL,
                    amoled = amoled,
                )
            assertTrue(contrastRatio(content, style.sentenceFill) >= WCAG_AA_NORMAL_TEXT_CONTRAST)
            assertTrue(
                contrastRatio(content, ttsInlineDecorationSurface(style.sentenceFill, content)) >=
                    WCAG_AA_NORMAL_TEXT_CONTRAST,
            )
            assertTrue(contrastRatio(style.sentenceMarker, background) >= WCAG_NON_TEXT_CONTRAST)
            assertTrue(contrastRatio(style.sentenceMarker, style.sentenceFill) >= WCAG_NON_TEXT_CONTRAST)
            assertTrue(contrastRatio(style.wordMarker, style.sentenceFill) >= WCAG_NON_TEXT_CONTRAST)
            assertTrue(contrastRatio(style.wordMarker, background) >= WCAG_NON_TEXT_CONTRAST)
            // The band must be visible on every bubble, AMOLED included. It
            // used to be assigned the background there, which made the rails
            // the only cue; with the rails gone that left no cue at all.
            assertTrue(style.sentenceFill != background)
            if (amoled) {
                assertEquals(0L, style.sentenceMarker and 0xFFL)
                assertEquals(0L, style.wordMarker and 0xFFL)
            }
        }
    }

    @Test
    fun opaqueCustomColorGridAlwaysResolvesPassingPaint() {
        for (red in 0..255 step 17) {
            for (green in 0..255 step 17) {
                for (blue in 0..255 step 17) {
                    val background = 0xFF000000L or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
                    val content = readableTextArgb(background) ?: continue
                    val style =
                        resolveTtsReadAloudHighlightStyle(
                            background = background,
                            content = content,
                            sentenceAccent = 0xFF777777L,
                            wordAccent = 0xFF008A92L,
                            amoled = false,
                        )
                    assertTrue(contrastRatio(content, style.sentenceFill) >= WCAG_AA_NORMAL_TEXT_CONTRAST)
                    assertTrue(
                        contrastRatio(content, ttsInlineDecorationSurface(style.sentenceFill, content)) >=
                            WCAG_AA_NORMAL_TEXT_CONTRAST,
                    )
                    assertTrue(contrastRatio(style.sentenceMarker, background) >= WCAG_NON_TEXT_CONTRAST)
                    assertTrue(contrastRatio(style.sentenceMarker, style.sentenceFill) >= WCAG_NON_TEXT_CONTRAST)
                    assertTrue(contrastRatio(style.wordMarker, style.sentenceFill) >= WCAG_NON_TEXT_CONTRAST)
                    assertTrue(contrastRatio(style.wordMarker, background) >= WCAG_NON_TEXT_CONTRAST)
                }
            }
        }
    }

    @Test
    fun amoledDoesNotApplyTrueBlackPaintToColoredBubble() {
        val background = 0xFF3F4849L
        val content = 0xFFBEC8C9L
        val style =
            resolveTtsReadAloudHighlightStyle(
                background = background,
                content = content,
                sentenceAccent = 0xFF777700L,
                wordAccent = 0xFF00FFFFL,
                amoled = true,
            )

        assertTrue(style.sentenceFill != background)
        assertTrue(style.sentenceMarker and 0xFFL != 0L || style.wordMarker and 0xFFL != 0L)
    }
}
