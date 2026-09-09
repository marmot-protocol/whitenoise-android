package dev.ipf.whitenoise.android.audio.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Rendered leaf every corpus case is projected from. */
internal const val CORPUS_LEAF_ID = "b0/n0"

internal fun verbalizeCorpusCase(case: SpeechCorpusCase): VerbalizedText =
    SpokenForms.verbalize(
        source = case.input,
        leafId = CORPUS_LEAF_ID,
        context = case.context,
    )

internal fun assertCorpusCases(cases: List<SpeechCorpusCase>) = cases.forEach(::assertCorpusCase)

internal fun assertCorpusCase(case: SpeechCorpusCase) {
    val verbalized = verbalizeCorpusCase(case)
    val spoken = verbalized.text
    case.expectedSpokenCore?.let { expected ->
        assertEquals("${case.id} spoken core", expected, spokenCoreOf(spoken))
    }
    case.expectedContains.forEach { fragment ->
        assertTrue(
            "${case.id}: expected '$fragment' in '$spoken'",
            spoken.contains(fragment, ignoreCase = true),
        )
    }
    case.expectedContainsAny.forEach { alternatives ->
        assertTrue(
            "${case.id}: expected one of $alternatives in '$spoken'",
            alternatives.any { spoken.contains(it, ignoreCase = true) },
        )
    }
    case.expectedAbsent.forEach { fragment ->
        assertFalse(
            "${case.id}: '$fragment' must not appear in '$spoken'",
            spoken.contains(fragment, ignoreCase = true),
        )
    }
    if (case.expectedLiteral) assertLiteralProtection(case, verbalized)
}

/**
 * A rejected or unsupported structure keeps its complete source token: the
 * exact characters are still spoken and every non-space source offset is owned
 * by an identity run, so no later rule reinterpreted part of it.
 */
internal fun assertLiteralProtection(
    case: SpeechCorpusCase,
    verbalized: VerbalizedText,
) {
    assertTrue(
        "${case.id}: literal token '${case.input}' must survive in '${verbalized.text}'",
        verbalized.text.contains(case.input),
    )
    val identityOffsets = verbalized.identitySourceOffsets(CORPUS_LEAF_ID)
    val required = case.input.indices.filterNot { case.input[it].isWhitespace() }
    assertTrue(
        "${case.id}: identity provenance must cover the whole protected token, got $identityOffsets",
        identityOffsets.containsAll(required),
    )
}

internal fun VerbalizedText.identitySourceOffsets(leafId: String): Set<Int> =
    runs
        .filter { it.kind == SpeechMappingKind.Identity }
        .flatMap(SpokenOriginRun::sources)
        .filter { it.leafId == leafId }
        .flatMap { it.start until it.end }
        .toSet()

internal fun VerbalizedText.replacementSourceSpans(leafId: String): List<SpeechSourceSpan> =
    runs
        .filter { it.kind == SpeechMappingKind.Replacement }
        .flatMap(SpokenOriginRun::sources)
        .filter { it.leafId == leafId }
