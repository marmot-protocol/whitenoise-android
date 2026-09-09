package dev.ipf.whitenoise.android.audio.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Code has its own speech role: prose date/money rules never apply, and no
 * code block is silently skipped.
 */
class CodeSpeechTest {
    @Test
    fun identifierBoundariesAndAssignmentAreAudibleWithoutInventingCurrency() {
        val narration = narrate("val totalPrice = 12.50")

        assertTrue(narration.text.contains("total", ignoreCase = true))
        assertTrue(narration.text.contains("price", ignoreCase = true))
        assertTrue(narration.text.contains("equals", ignoreCase = true))
        assertTrue(narration.text.contains("twelve point five zero", ignoreCase = true))
        assertFalse(narration.text.contains("dollars", ignoreCase = true))
    }

    @Test
    fun snakeCaseAndMemberAccessKeepTheirBoundaries() {
        val narration = narrate("client.sendMessage(user_id)", role = SpeechRole.InlineCode)

        listOf("client", "dot", "send", "message", "user", "id").forEach { fragment ->
            assertTrue("expected '$fragment' in '${narration.text}'", narration.text.contains(fragment, true))
        }
        assertTrue(narration.text.contains("parenthes", ignoreCase = true))
    }

    @Test
    fun aDollarLiteralInCodeIsNotProse() {
        val narration = narrate("echo \"\$5\"")

        assertTrue(narration.text.contains("dollar", ignoreCase = true))
        assertTrue(narration.text.contains("five", ignoreCase = true))
        assertFalse(narration.text.contains("five dollars", ignoreCase = true))
    }

    @Test
    fun aDateShapedExpressionInCodeReadsItsOperators() {
        val narration = narrate("2026-05-09", role = SpeechRole.InlineCode)

        assertEquals(2, Regex("minus", RegexOption.IGNORE_CASE).findAll(narration.text).count())
        assertFalse(narration.text.contains("May", ignoreCase = true))
    }

    @Test
    fun aDateShapedStringLiteralStaysAQuotedLiteral() {
        val narration = narrate("date = \"2026-09-08\"")

        assertTrue(narration.text.contains("string", ignoreCase = true) || narration.text.contains("quote", true))
        assertFalse(narration.text.contains("September", ignoreCase = true))
    }

    @Test
    fun longestOperatorMatchWinsAndNoSyntaxIsDropped() {
        val narration = narrate("if (x != 0 && y <= 10) { y++; }")

        listOf("not equal", "and", "less than or equal", "increment").forEach { fragment ->
            assertTrue("expected '$fragment' in '${narration.text}'", narration.text.contains(fragment, true))
        }
        assertFalse(
            "'!=' must not degrade into a bare equality",
            narration.text.contains("equals zero", ignoreCase = true),
        )
    }

    @Test
    fun hexAndBinaryLiteralsKeepTheirBaseLabelAndLeadingZeros() {
        val narration = narrate("0x0F + 0b01")

        assertTrue(narration.text.contains("hex", ignoreCase = true))
        assertTrue(narration.text.contains("binary", ignoreCase = true))
        assertTrue(narration.text.contains("zero", ignoreCase = true))
        assertTrue(narration.text.contains("plus", ignoreCase = true))
    }

    @Test
    fun lineBoundariesAndIndentChangesAreAnnouncedWithoutVoicingEverySpace() {
        val narration = narrate("if ready:\n    send()\nstop()")

        assertTrue(narration.text.contains("indent", ignoreCase = true))
        assertTrue(narration.text.contains("dedent", ignoreCase = true) || narration.text.contains("outdent", true))
        assertFalse(narration.text.contains("space space", ignoreCase = true))
    }

    @Test
    fun everyCodeLineIsANavigableUnitWithProvenance() {
        val source = "if ready:\n    send()\nstop()"

        val narration = narrate(source)

        assertEquals(
            "every narrated character must trace back to code or be explicitly synthetic",
            emptyList<SpokenOriginRun>(),
            narration.runs.filter { it.kind != SpeechMappingKind.Synthetic && it.sources.isEmpty() },
        )
        val covered = narration.identitySourceOffsets(CORPUS_LEAF_ID) + narration.replacementOffsets()
        assertTrue(
            "the final line must be narrated",
            covered.containsAll((source.indexOf("stop") until source.length).toList()),
        )
    }

    @Test
    fun literalModeSpellsExactCharactersAndDefaultModeDoesNot() {
        val source = "y++;"

        val default = narrate(source)
        val literal =
            CodeSpeech.narrate(
                source = source,
                leafId = CORPUS_LEAF_ID,
                languageTag = null,
                role = SpeechRole.CodeBlock,
                context = SpeechContext(voiceLocale = Locale.US, mode = SpeechMode.LiteralCode),
            )

        assertTrue(literal.text.contains("semicolon", ignoreCase = true))
        assertFalse(
            "literal mode must not be the default reading",
            default.text == literal.text,
        )
    }

    @Test
    fun anUnknownLanguageUsesTheGenericLexerInSourceOrder() {
        val narration =
            CodeSpeech.narrate(
                source = "fn main() { 42 }",
                leafId = CORPUS_LEAF_ID,
                languageTag = "some-unknown-language",
                role = SpeechRole.CodeBlock,
                context = SpeechContext(voiceLocale = Locale.US),
            )

        assertTrue(narration.text.contains("main", ignoreCase = true))
        assertTrue(narration.text.contains("forty-two", ignoreCase = true))
    }

    private fun narrate(
        source: String,
        role: SpeechRole = SpeechRole.CodeBlock,
        languageTag: String? = null,
    ): VerbalizedText =
        CodeSpeech.narrate(
            source = source,
            leafId = CORPUS_LEAF_ID,
            languageTag = languageTag,
            role = role,
            context = SpeechContext(voiceLocale = Locale.US),
        )

    private fun VerbalizedText.replacementOffsets(): Set<Int> =
        replacementSourceSpans(CORPUS_LEAF_ID)
            .flatMap {
                it.start until
                    it.end
            }.toSet()
}
