package dev.ipf.whitenoise.android.audio.tts.speech

import dev.ipf.whitenoise.android.audio.tts.TtsTextRange
import java.util.Locale

const val SPEECH_VERBALIZER_VERSION = 1
const val SPEECH_PREPARATION_VERSION = 1
const val SPEECH_PREPARATION_MAX_OUTPUT_CHARS = 32_000
const val SPEECH_PREPARATION_MAX_NODES = 4_096

enum class SpeechRole { Prose, InlineCode, CodeBlock, Diagram, Math }

enum class SpeechMode { Default, LiteralCode }

enum class SpeechSemanticHint {
    None,
    Year,
    YearMonth,
    OrdinalDate,
    Clock,
    Duration,
    Fraction,
    Number,
    AccountingAmount,
    Unknown,
}

enum class SpeechLocaleSupport { Verbalized, Literal }

enum class SpeechMappingKind { Identity, Replacement, Synthetic }

data class SpeechContext(
    val voiceLocale: Locale,
    val sourceFormatLocale: Locale? = null,
    val semanticHint: SpeechSemanticHint = SpeechSemanticHint.None,
    val mode: SpeechMode = SpeechMode.Default,
    val verbalizerVersion: Int = SPEECH_VERBALIZER_VERSION,
)

sealed interface SpeechStructure {
    data class TableHeader(
        val column: Int,
    ) : SpeechStructure

    data class TableCell(
        val row: Int,
        val column: Int,
    ) : SpeechStructure
}

data class SpeechSourceRun(
    val leafId: String,
    val text: String,
    val role: SpeechRole,
    val languageTag: String? = null,
    val structure: SpeechStructure? = null,
)

data class SpeechSourceSpan(
    val leafId: String,
    val start: Int,
    val end: Int,
) {
    init {
        require(leafId.isNotEmpty() && start >= 0 && end > start)
    }
}

data class SpokenOriginRun(
    val spoken: TtsTextRange,
    val sources: List<SpeechSourceSpan>,
    val kind: SpeechMappingKind,
) {
    init {
        require(spoken.end > spoken.start)
        require(if (kind == SpeechMappingKind.Synthetic) sources.isEmpty() else sources.isNotEmpty())
        require(kind != SpeechMappingKind.Identity || sources.sumOf { it.end - it.start } == spoken.end - spoken.start)
    }
}

data class VerbalizedText(
    val text: String,
    val runs: List<SpokenOriginRun>,
    val localeSupport: SpeechLocaleSupport = SpeechLocaleSupport.Verbalized,
)

data class SpeechProvenance(
    val sourceRevisionId: String,
    val effectiveLanguageTag: String,
    val mode: SpeechMode,
    val verbalizerVersion: Int,
    val preparationVersion: Int = SPEECH_PREPARATION_VERSION,
    val sourceFormatLanguageTag: String? = null,
    val semanticHint: SpeechSemanticHint = SpeechSemanticHint.None,
)

internal fun SpeechContext.provenance(revision: String) =
    SpeechProvenance(
        revision,
        voiceLocale.toLanguageTag(),
        mode,
        verbalizerVersion,
        sourceFormatLanguageTag = sourceFormatLocale?.toLanguageTag(),
        semanticHint = semanticHint,
    )

data class PreparedUtterance(
    val engineText: String,
    val originRuns: List<SpokenOriginRun>,
    val provenance: SpeechProvenance,
    val senderPrefix: TtsTextRange? = null,
) {
    val spokenWords: List<TtsTextRange> get() =
        Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)*")
            .findAll(engineText)
            .map {
                TtsTextRange(
                    it.range.first,
                    it.range.last + 1,
                )
            }.toList()

    fun sourceSpansForSpoken(range: TtsTextRange): List<SpeechSourceSpan> =
        originRuns
            .flatMap {
                it.slice(range)?.sources.orEmpty()
            }.distinct()
}

internal fun SpokenOriginRun.slice(
    range: TtsTextRange,
    shift: Int = 0,
): SpokenOriginRun? {
    val start = maxOf(spoken.start, range.start)
    val end = minOf(spoken.end, range.end)
    if (end <= start) return null
    val mapped =
        if (kind != SpeechMappingKind.Identity) {
            sources
        } else {
            var offset = spoken.start
            sources.mapNotNull { source ->
                val a = maxOf(start, offset)
                val b = minOf(end, offset + source.end - source.start)
                val result =
                    if (a <
                        b
                    ) {
                        source.copy(start = source.start + a - offset, end = source.start + b - offset)
                    } else {
                        null
                    }
                offset += source.end - source.start
                result
            }
        }
    return copy(spoken = TtsTextRange(start - shift, end - shift), sources = mapped)
}

data class PreparedSentence(
    val sentenceId: String,
    val ordinal: Int,
    val utterance: PreparedUtterance,
    val role: SpeechRole,
)

data class PreparedSpeechMessage(
    val messageIdHex: String,
    val sentences: List<PreparedSentence>,
    val provenance: SpeechProvenance,
    val displayPreview: String,
    val sourceRuns: List<SpeechSourceRun>,
) {
    fun forwardSentenceIdsForSource(
        leafId: String,
        offset: Int,
    ): List<String> =
        sentences
            .sortedBy { it.ordinal }
            .filter { sentence ->
                sentence.utterance.originRuns.any { run ->
                    run.sources.any {
                        it.leafId ==
                            leafId &&
                            offset in it.start until it.end
                    }
                }
            }.map { it.sentenceId }
            .distinct()

    fun canonicalSentenceIdForSource(
        leafId: String,
        offset: Int,
    ): String? {
        forwardSentenceIdsForSource(leafId, offset).firstOrNull()?.let { return it }
        val spans =
            sentences
                .flatMap { it.utterance.originRuns }
                .flatMap { it.sources }
                .filter { it.leafId == leafId }
                .sortedBy { it.start }
        val neighbor = spans.firstOrNull { it.start >= offset } ?: spans.lastOrNull()
        return neighbor?.let { forwardSentenceIdsForSource(leafId, it.start).firstOrNull() }
    }

    fun matchesSpeechIdentity(
        context: SpeechContext,
        sourceRevisionId: String,
    ): Boolean = provenance == context.provenance(sourceRevisionId)
}

internal class NarrationBuilder {
    private val text = StringBuilder()
    val length: Int get() = text.length
    private val runs = mutableListOf<SpokenOriginRun>()

    fun add(
        value: String,
        sources: List<SpeechSourceSpan> = emptyList(),
        kind: SpeechMappingKind = SpeechMappingKind.Synthetic,
    ) {
        if (value.isEmpty()) return
        val start = text.length
        text.append(value)
        runs += SpokenOriginRun(TtsTextRange(start, text.length), sources, kind)
    }

    fun append(value: VerbalizedText) {
        val offset = text.length
        text.append(value.text)
        runs += value.runs.map { it.copy(spoken = TtsTextRange(it.spoken.start + offset, it.spoken.end + offset)) }
    }

    fun build(support: SpeechLocaleSupport = SpeechLocaleSupport.Verbalized) =
        VerbalizedText(
            text = text.toString(),
            runs = runs.toList(),
            localeSupport = support,
        )
}

internal fun literalText(
    source: String,
    leafId: String,
    support: SpeechLocaleSupport = SpeechLocaleSupport.Verbalized,
) = VerbalizedText(
    source,
    if (source.isEmpty()) {
        emptyList()
    } else {
        listOf(
            SpokenOriginRun(
                TtsTextRange(0, source.length),
                listOf(SpeechSourceSpan(leafId, 0, source.length)),
                SpeechMappingKind.Identity,
            ),
        )
    },
    support,
)
