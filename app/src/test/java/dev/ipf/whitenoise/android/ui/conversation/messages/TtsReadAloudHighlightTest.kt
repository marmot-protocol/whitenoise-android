package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.text.TextRange
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.state.readableTextArgb
import dev.ipf.whitenoise.android.state.tonalBubbleColorPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

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
    fun opaqueSrgbCompositingMatchesTheFormerFailingLightIncomingPixels() {
        val sentence = blendOpaqueArgb(0xFFDBE4E5, 0xFF00696E, 0.28f)
        val word = blendOpaqueArgb(sentence, 0xFF00696E, 0.72f)

        assertEquals(0xFF9EC2C4, sentence)
        assertEquals(0xFF2C8286, word)
    }

    @Test
    fun lockedBubbleMatrixKeepsTextAndStateMarkersContrastSafe() {
        val cases =
            listOf(
                ContrastCase("light incoming", 0xFFDBE4E5, 0xFF3F4849, 0xFFBFC8C9, 0xFF00696E),
                ContrastCase("light outgoing", 0xFF06B6D4, 0xFF001F28, 0xFFBFC8C9, 0xFF00696E),
                ContrastCase("dark incoming", 0xFF3F4849, 0xFFBEC8C9, 0xFF3F4849, 0xFF7FD4E0),
                ContrastCase("dark outgoing", 0xFF06B6D4, 0xFF001F28, 0xFF3F4849, 0xFF7FD4E0),
                ContrastCase("light error", 0xFFFFDAD6, 0xFF410002, 0xFFBFC8C9, 0xFF00696E),
                ContrastCase("dark error", 0xFF5C1A1A, 0xFFFFD9D6, 0xFF3F4849, 0xFF7FD4E0),
                ContrastCase("AMOLED", 0xFF000000, 0xFFB0A000, 0xFF665A00, 0xFFF5E600, amoled = true),
                ContrastCase("AMOLED error", 0xFF4A1200, 0xFFFFB000, 0xFF665A00, 0xFFF5E600, amoled = true),
                ContrastCase("dynamic light", 0xFFEADDFF, 0xFF21005D, 0xFFCAC4D0, 0xFF7D5260),
                ContrastCase("dynamic dark", 0xFF4A4458, 0xFFE8DEF8, 0xFF49454F, 0xFFEFB8C8),
                ContrastCase("AMOLED account accent", 0xFF000000, 0xFFB0A000, 0xFF665A00, 0xFFFF7A00, amoled = true),
            )

        cases.forEach { case ->
            val resolved = assertContrastSafe(case)
            if (case.amoled) assertBlueFree(case.label, resolved)
        }
    }

    @Test
    fun everyQuickSwatchCustomBubbleRemainsContrastSafe() {
        tonalBubbleColorPresets().forEach { background ->
            val content = checkNotNull(readableTextArgb(background))
            assertContrastSafe(
                ContrastCase(
                    label = "quick swatch ${background.toString(16)}",
                    background = background,
                    content = content,
                    sentenceAccent = 0xFFBFC8C9,
                    wordAccent = 0xFF00696E,
                ),
            )
        }
    }

    @Test
    fun customAndDynamicPaletteSamplesAlwaysResolveCheckedColors() {
        val random = Random(0x2237_5454_53L)
        repeat(4_096) { sample ->
            val background = 0xFF000000L or random.nextInt(0x1000000).toLong()
            val content = checkNotNull(readableTextArgb(background))
            val sentenceAccent = 0xFF000000L or random.nextInt(0x1000000).toLong()
            val wordAccent = 0xFF000000L or random.nextInt(0x1000000).toLong()

            assertContrastSafe(
                ContrastCase(
                    label = "synthetic palette $sample",
                    background = background,
                    content = content,
                    sentenceAccent = sentenceAccent,
                    wordAccent = wordAccent,
                ),
            )
        }
    }

    @Test
    fun amoledKeepsTrueBlackFillAndBlueFreeWarmMarkers() {
        val resolved =
            resolveTtsReadAloudHighlightStyleArgb(
                backgroundArgb = 0xFF000000,
                contentArgb = 0xFFB0A000,
                sentenceAccentArgb = 0xFF665A00,
                wordAccentArgb = 0xFFF5E600,
                amoled = true,
            )

        assertEquals(0xFF000000, resolved.sentenceFillArgb)
        assertEquals(0L, resolved.sentenceMarkerArgb and 0xFF)
        assertEquals(0L, resolved.wordMarkerArgb and 0xFF)
    }

    @Test
    fun amoledResolverRejectsCyanCandidatesAtItsOwnBoundary() {
        val resolved =
            resolveTtsReadAloudHighlightStyleArgb(
                backgroundArgb = 0xFF000000,
                contentArgb = 0xFF00FFFF,
                sentenceAccentArgb = 0xFF00FFFF,
                wordAccentArgb = 0xFF00FFFF,
                amoled = true,
            )

        assertEquals(0xFF000000, resolved.sentenceFillArgb)
        assertBlueFree("adversarial AMOLED cyan", resolved)
        assertContrastAtLeast("adversarial AMOLED cyan", resolved.sentenceMarkerArgb, 0xFF000000, 3.0)
        assertContrastAtLeast("adversarial AMOLED cyan", resolved.wordMarkerArgb, 0xFF000000, 3.0)
    }

    @Test
    fun uncheckedAccentCandidatesFallBackToTheValidatedContentRole() {
        val content = 0xFF001F28L
        val resolved =
            resolveTtsReadAloudHighlightStyleArgb(
                backgroundArgb = 0xFF06B6D4,
                contentArgb = content,
                sentenceAccentArgb = 0xFF06B6D4,
                wordAccentArgb = 0xFF06B6D4,
                amoled = false,
            )

        assertEquals(content, resolved.sentenceMarkerArgb)
        assertEquals(content, resolved.wordMarkerArgb)
    }

    @Test
    fun resolverIsDeterministicForTheSameFinalRoles() {
        val first =
            resolveTtsReadAloudHighlightStyleArgb(
                backgroundArgb = 0xFF7B3FC6,
                contentArgb = checkNotNull(readableTextArgb(0xFF7B3FC6)),
                sentenceAccentArgb = 0xFF00696E,
                wordAccentArgb = 0xFF7FD4E0,
                amoled = false,
            )
        val second =
            resolveTtsReadAloudHighlightStyleArgb(
                backgroundArgb = 0xFF7B3FC6,
                contentArgb = checkNotNull(readableTextArgb(0xFF7B3FC6)),
                sentenceAccentArgb = 0xFF00696E,
                wordAccentArgb = 0xFF7FD4E0,
                amoled = false,
            )

        assertEquals(first, second)
    }

    private fun assertContrastSafe(case: ContrastCase): TtsReadAloudHighlightStyleArgb {
        val resolved =
            resolveTtsReadAloudHighlightStyleArgb(
                backgroundArgb = case.background,
                contentArgb = case.content,
                sentenceAccentArgb = case.sentenceAccent,
                wordAccentArgb = case.wordAccent,
                amoled = case.amoled,
            )
        val inlineDecorationBackground =
            compositeOpaqueArgb(
                foregroundArgb = case.content,
                backgroundArgb = resolved.sentenceFillArgb,
                foregroundAlpha = 0.12f,
            )

        assertContrastAtLeast(case.label, case.content, resolved.sentenceFillArgb, WCAG_AA_NORMAL_TEXT_CONTRAST)
        assertContrastAtLeast(case.label, case.content, inlineDecorationBackground, WCAG_AA_NORMAL_TEXT_CONTRAST)
        assertContrastAtLeast(case.label, resolved.sentenceMarkerArgb, case.background, 3.0)
        assertContrastAtLeast(case.label, resolved.sentenceMarkerArgb, resolved.sentenceFillArgb, 3.0)
        assertContrastAtLeast(case.label, resolved.wordMarkerArgb, resolved.sentenceFillArgb, 3.0)
        return resolved
    }

    private fun assertBlueFree(
        label: String,
        resolved: TtsReadAloudHighlightStyleArgb,
    ) {
        assertEquals("$label sentence marker must remain blue-free", 0L, resolved.sentenceMarkerArgb and 0xFF)
        assertEquals("$label word marker must remain blue-free", 0L, resolved.wordMarkerArgb and 0xFF)
    }

    private fun assertContrastAtLeast(
        label: String,
        foreground: Long,
        background: Long,
        minimum: Double,
    ) {
        val actual = contrastRatio(foreground, background)
        assertTrue("$label contrast $actual was below $minimum", actual >= minimum)
    }

    private data class ContrastCase(
        val label: String,
        val background: Long,
        val content: Long,
        val sentenceAccent: Long,
        val wordAccent: Long,
        val amoled: Boolean = false,
    )
}
