package dev.ipf.whitenoise.android.audio.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Recognized diagrams get a bounded faithful narration; anything outside the
 * grammar falls back to explicit line-by-line reading instead of an invented
 * topology.
 */
class DiagramSpeechTest {
    @Test
    fun singleLineDirectedArrowsNarrateEachRelationInOrder() {
        val narration = narrate("Client -> Relay -> Receiver")

        assertTrue(narration.recognized)
        assertEquals(
            "Client points to Relay. Relay points to Receiver.",
            narration.verbalized.text.trim(),
        )
        assertEquals(2, narration.relations.size)
    }

    @Test
    fun everyDiagramLabelKeepsItsExactSourceSpan() {
        val source = "Client -> Relay -> Receiver"
        val relayStart = source.indexOf("Relay")

        val narration = narrate(source)

        val relaySpans =
            narration.verbalized
                .replacementSourceSpans(CORPUS_LEAF_ID)
                .filter { it.start == relayStart }
        assertEquals(
            "the shared Relay label is narrated twice and both references keep the one source span",
            2,
            relaySpans.size,
        )
        assertTrue(relaySpans.all { it.end == relayStart + "Relay".length })
    }

    @Test
    fun boxDrawingTreesNarrateBranchStructureWithoutDuplicatingLabels() {
        val narration = narrate("Root\n├── Left\n└── Right")

        assertTrue(narration.recognized)
        listOf("Root", "Left", "Right").forEach { label ->
            assertEquals(
                "$label must be narrated exactly once",
                1,
                Regex(Regex.escape(label)).findAll(narration.verbalized.text).count(),
            )
        }
    }

    @Test
    fun crossingEdgesAndAmbiguousConnectorsFallBackToLineByLineReading() {
        val source = "A ---+--- B\n     |\n C --X--> D"

        val narration = narrate(source)

        assertFalse(narration.recognized)
        assertTrue(narration.verbalized.text.contains("Diagram, reading line by line"))
        listOf("A", "B", "C", "D").forEach { label ->
            assertTrue("label $label must be retained", narration.verbalized.text.contains(label))
        }
        assertFalse(
            "an unsupported crossing must not become a relation",
            narration.verbalized.text.contains("points to"),
        )
        assertEquals(emptyList<DiagramRelation>(), narration.relations)
    }

    @Test
    fun aDecorativeBorderRunIsDescribedWithACountInsteadOfRepeatedWords() {
        val narration = narrate("+--------------------+\n| Box |\n+--------------------+")

        val dashWords = Regex("dash", RegexOption.IGNORE_CASE).findAll(narration.verbalized.text).count()
        assertTrue("a border run must be summarized, got $dashWords dash words", dashWords <= 4)
        assertTrue(narration.verbalized.text.contains("Box"))
    }

    @Test
    fun aFencedCodeBlockUsingArrowsIsNotADiagram() {
        val narration =
            DiagramSpeech.narrate(
                source = "x -> x + 1",
                leafId = CORPUS_LEAF_ID,
                languageTag = "kotlin",
                context = SpeechContext(voiceLocale = Locale.US),
            )

        assertFalse("an explicit language fence wins over the diagram grammar", narration.recognized)
    }

    @Test
    fun aPartialGrammarMatchDoesNotPromoteTheWholeBlock() {
        val narration = narrate("Client -> Relay\nsome unrelated prose line without connectors")

        assertFalse("a whole-block grammar match is required", narration.recognized)
        assertTrue(narration.verbalized.text.contains("Diagram, reading line by line"))
    }

    @Test
    fun whitespaceOnlyArrowLabelsDoNotCreateEmptySourceSpans() {
        val narration = narrate("  -> Relay")

        assertFalse(narration.recognized)
        assertTrue(narration.verbalized.runs.none { run -> run.sources.any { it.start == it.end } })
    }

    @Test
    fun narratedDiagramTextHasNoUnattributedLexicalContent() {
        val narration = narrate("Client -> Relay -> Receiver")

        assertEquals(
            emptyList<SpokenOriginRun>(),
            narration.verbalized.runs.filter {
                it.kind != SpeechMappingKind.Synthetic && it.sources.isEmpty()
            },
        )
    }

    private fun narrate(source: String): DiagramNarration =
        DiagramSpeech.narrate(
            source = source,
            leafId = CORPUS_LEAF_ID,
            languageTag = null,
            context = SpeechContext(voiceLocale = Locale.US),
        )
}
