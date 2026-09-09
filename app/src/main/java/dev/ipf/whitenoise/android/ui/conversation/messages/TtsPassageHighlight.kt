@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.messages

import android.speech.tts.TextToSpeech
import dev.ipf.whitenoise.android.audio.tts.TtsChunk
import dev.ipf.whitenoise.android.audio.tts.TtsChunker
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSpeechMessage
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.SpeakableTextProjectionSpan
import dev.ipf.whitenoise.android.ui.TtsLeafHighlight
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal fun ttsHighlightMaxChunkLength(): Int =
    runCatching { TextToSpeech.getMaxSpeechInputLength() }
        .getOrElse { TTS_HIGHLIGHT_FALLBACK_MAX_CHUNK_LENGTH }

private const val TTS_HIGHLIGHT_FALLBACK_MAX_CHUNK_LENGTH = 4_000

internal fun createTtsLeafHighlightResolver(
    passage: TtsPassage,
    messageIdHex: String,
    projection: SpeakableTextProjection,
    prepared: PreparedSpeechMessage,
): (String, String) -> TtsLeafHighlight? =
    TtsHighlightProjectionResolver(
        projection = projection,
        preparedSpeech = prepared,
    ).resolverFor(passage, messageIdHex)

/** Projection-level work shared by every word update in one active message. */
internal class TtsHighlightProjectionResolver(
    private val projection: SpeakableTextProjection,
    private val preparedSpeech: PreparedSpeechMessage,
) {
    private val sentenceChunks = projectionSentenceChunks(projection, preparedSpeech)
    private val leafSpanCache = HashMap<Pair<String, String>, List<RenderedProjectionSpan>?>()

    internal val cachedLeafCount: Int
        get() = leafSpanCache.size

    /** Inverts the highlight projection for an exact rendered-leaf hit. */
    @Suppress("ReturnCount")
    internal fun sentenceIndexAtRenderedOffset(
        hit: RenderedTextHit,
        allowOmittedLinkNeighbor: Boolean = false,
    ): Int? {
        val (leafId, nativeOffset) =
            sourceOffsetAtRenderedHit(projection, hit, allowOmittedLinkNeighbor, leafSpanCache) ?: return null
        val owner = preparedSpeech.canonicalSentenceIdForSource(leafId, nativeOffset)
        return preparedSpeech.sentences.firstOrNull { it.sentenceId == owner }?.ordinal
    }

    internal fun sentenceChoices(
        leafId: String,
        original: String,
    ): List<dev.ipf.whitenoise.android.ui.TtsSentenceChoice> =
        preparedSpeech.sentences
            .asSequence()
            .mapNotNull { sentence ->
                val ownsSource =
                    sentence.utterance.originRuns.any { origin ->
                        origin.sources.any { source ->
                            source.leafId.belongsToRenderedLeaf(leafId) &&
                                preparedSpeech.canonicalSentenceIdForSource(source.leafId, source.start) ==
                                sentence.sentenceId
                        }
                    }
                if (!ownsSource) return@mapNotNull null
                val layout = sentenceLayoutFor(sentence.ordinal, leafId, original) ?: return@mapNotNull null
                val excerpt =
                    layout.renderedRanges
                        .joinToString(" ") { range ->
                            original.substring(
                                range.first.coerceIn(0, original.length),
                                (range.last + 1).coerceIn(0, original.length),
                            )
                        }.take(TTS_SENTENCE_EXCERPT_LENGTH)
                dev.ipf.whitenoise.android.ui
                    .TtsSentenceChoice(projection.projectionId, sentence.sentenceId, sentence.ordinal, excerpt)
            }.take(TTS_SENTENCE_CHOICE_LIMIT)
            .toList()

    internal fun resolverFor(
        passage: TtsPassage,
        messageIdHex: String,
    ): (String, String) -> TtsLeafHighlight? =
        { renderedLeafId, renderedText ->
            resolveTtsRenderedHighlights(
                passage = passage,
                messageIdHex = messageIdHex,
                projection = projection,
                renderedLeafId = renderedLeafId,
                renderedText = renderedText,
                sentenceChunks = sentenceChunks,
                leafSpanCache = leafSpanCache,
                prepared = preparedSpeech,
            )
        }

    @Suppress("ReturnCount")
    internal fun sentenceLayoutFor(
        sentenceIndex: Int,
        renderedLeafId: String,
        renderedText: String,
    ): TtsSentenceLeafLayout? {
        if (renderedText.isEmpty()) return null
        val intervals = sentenceProjectionIntervals(sentenceIndex, sentenceChunks, projection, preparedSpeech)
        if (intervals.isEmpty()) return null
        val mappedSpans =
            mapProjectionSpansToRenderedLeaf(
                projection = projection,
                renderedLeafId = renderedLeafId,
                renderedText = renderedText,
                leafSpanCache = leafSpanCache,
            ) ?: return null
        val expectedCoverage = intervals.flatMap { projection.sentenceCoverage(it) }.toSet()
        val coverage = intervals.flatMap { mappedSpans.sentenceCoverage(it) }.toSet()
        if (expectedCoverage.isEmpty() || coverage.isEmpty()) return null
        val ranges = intervals.flatMap { mappedSpans.sentenceRenderedRanges(it) }.distinct()
        if (ranges.isEmpty()) return null
        return TtsSentenceLeafLayout(
            renderedRanges = ranges,
            coverage = coverage,
            expectedCoverage = expectedCoverage,
        )
    }
}

internal data class TtsSentenceProjectionSegment(
    val leafId: String,
    val spokenStart: Int,
    val spokenEnd: Int,
)

internal data class TtsSentenceLeafLayout(
    val renderedRanges: List<IntRange>,
    val coverage: Set<TtsSentenceProjectionSegment>,
    val expectedCoverage: Set<TtsSentenceProjectionSegment>,
)

/**
 * Resolves the active read-aloud passage into a half-open UTF-16 range inside
 * one rendered text leaf. Returns null when identity does not match, the leaf is
 * unavailable, or the mapping cannot be reconstructed exactly.
 */
@Suppress("ReturnCount")
internal fun resolveTtsRenderedHighlight(
    passage: TtsPassage?,
    messageIdHex: String,
    projection: SpeakableTextProjection,
    renderedLeafId: String,
    renderedText: String,
    locale: Locale,
): IntRange? {
    // Compatibility callers supply legacy cell ordinals, without a prepared session.
    val sentenceChunks = TtsChunker.chunk(projection.text, locale, maxChunkLength = ttsHighlightMaxChunkLength())
    return resolveTtsRenderedHighlights(
        passage = passage,
        messageIdHex = messageIdHex,
        projection = projection,
        renderedLeafId = renderedLeafId,
        renderedText = renderedText,
        sentenceChunks = sentenceChunks,
        leafSpanCache = null,
    )?.primaryRange
}

@Suppress("ReturnCount")
private fun resolveTtsRenderedHighlights(
    passage: TtsPassage?,
    messageIdHex: String,
    projection: SpeakableTextProjection,
    renderedLeafId: String,
    renderedText: String,
    sentenceChunks: List<TtsChunk>,
    leafSpanCache: MutableMap<Pair<String, String>, List<RenderedProjectionSpan>?>?,
    prepared: dev.ipf.whitenoise.android.audio.tts.speech.PreparedSpeechMessage? = null,
): TtsLeafHighlight? {
    if (passage == null || passage.messageIdHex != messageIdHex) return null
    if (passage.projectionId != projection.projectionId) return null
    if (renderedText.isEmpty()) return null
    val mappedSpans =
        mapProjectionSpansToRenderedLeaf(
            projection = projection,
            renderedLeafId = renderedLeafId,
            renderedText = renderedText,
            leafSpanCache = leafSpanCache,
        )
            ?: return null
    // Every rendered range the sentence covers, not one contiguous span:
    // rendered content the projection never speaks - an omitted URL or
    // collapsed punctuation - legitimately splits one spoken sentence
    // into disjoint rendered pieces, and the same merge already feeds the
    // follow geometry in sentenceLayoutFor.
    val sentenceRanges =
        sentenceProjectionIntervals(passage.sentenceIndex, sentenceChunks, projection, prepared)
            .flatMap { mappedSpans.sentenceRenderedRanges(it) }
            .distinct()
    val word =
        passage.visibleWord
            .takeIf(List<TtsVisibleTextSpan>::isNotEmpty)
            ?.let { visibleWordHighlight(it, renderedLeafId, mappedSpans) }
    return TtsLeafHighlight(sentenceRanges = sentenceRanges, word = word).takeIf {
        it.sentenceRanges.isNotEmpty() || it.word != null
    }
}

/** Maps a composite Markdown selection to the source leaf used by background speech preparation. */
internal fun preparedHitFromRenderedHit(
    entry: dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry,
    hit: RenderedTextHit,
): dev.ipf.whitenoise.android.audio.tts.speech.PreparedRenderedHit? {
    val projection =
        SpeakableTextProjection(
            text = entry.text,
            spans =
                entry.spokenTextSpans.map { span ->
                    SpeakableTextProjectionSpan(
                        span.spoken.start,
                        span.spoken.end,
                        span.visible.leafId,
                        span.visible.start,
                        span.visible.end,
                    )
                },
            projectionId = entry.projectionId,
            visibleLeaves = entry.visibleLeaves,
            speechRoles = entry.speechRoles,
        )
    return sourceOffsetAtRenderedHit(projection, hit)?.let { (leafId, offset) ->
        projection.visibleLeaves[leafId]?.let { original ->
            dev.ipf.whitenoise.android.audio.tts.speech
                .PreparedRenderedHit(leafId, original, offset)
        }
    }
}

/** Shares exact rendered-to-source alignment between selection startup and active playback seeking. */
@Suppress("ReturnCount")
private fun sourceOffsetAtRenderedHit(
    projection: SpeakableTextProjection,
    hit: RenderedTextHit,
    allowOmittedLinkNeighbor: Boolean = false,
    leafSpanCache: MutableMap<Pair<String, String>, List<RenderedProjectionSpan>?>? = null,
): Pair<String, Int>? {
    if (hit.renderedOffset !in 0..hit.renderedText.length) return null
    val mappedSpans =
        mapProjectionSpansToRenderedLeaf(
            projection = projection,
            renderedLeafId = hit.leafId,
            renderedText = hit.renderedText,
            leafSpanCache = leafSpanCache,
        ) ?: return null
    val candidates =
        mappedSpans.filter { mapped ->
            val spokenLength = mapped.source.spokenEnd - mapped.source.spokenStart
            val visibleLength = mapped.source.visibleEnd - mapped.source.visibleStart
            mapped.source.leafId.belongsToRenderedLeaf(hit.leafId) &&
                spokenLength > 0 &&
                spokenLength == visibleLength
        }
    if (candidates.isEmpty()) return null
    if (projection.visibleLeaves.isNotEmpty() && !allowOmittedLinkNeighbor) {
        if (isOmittedHit(hit, candidates)) return null
    }
    val mapped =
        candidates.minByOrNull { candidate ->
            val end = candidate.renderedStart + candidate.source.spokenEnd - candidate.source.spokenStart
            when {
                hit.renderedOffset < candidate.renderedStart -> candidate.renderedStart - hit.renderedOffset
                hit.renderedOffset > end -> hit.renderedOffset - end
                else -> 0
            }
        } ?: return null
    val renderedEnd = mapped.renderedStart + mapped.source.spokenEnd - mapped.source.spokenStart
    val clampedRenderedOffset = hit.renderedOffset.coerceIn(mapped.renderedStart, renderedEnd - 1)
    val nativeOffset = mapped.source.visibleStart + clampedRenderedOffset - mapped.renderedStart
    return mapped.source.leafId to nativeOffset
}

private data class RenderedProjectionSpan(
    val source: SpeakableTextProjectionSpan,
    val renderedStart: Int,
)

private data class RenderedInterval(
    val start: Int,
    val end: Int,
)

/**
 * Places every projected segment for one rendered leaf exactly. Direct leaves
 * (plain/code/math) use their visible offsets. Composite Markdown leaves align
 * all child segments in order and require the forward and backward placements
 * to agree, so repeated/hidden content never causes a guessed range.
 */
private fun mapProjectionSpansToRenderedLeaf(
    projection: SpeakableTextProjection,
    renderedLeafId: String,
    renderedText: String,
    leafSpanCache: MutableMap<Pair<String, String>, List<RenderedProjectionSpan>?>? = null,
): List<RenderedProjectionSpan>? {
    val cacheKey = renderedLeafId to renderedText
    if (leafSpanCache != null && leafSpanCache.containsKey(cacheKey)) return leafSpanCache[cacheKey]
    val spans =
        projection.spans
            .filter { it.isValidForRenderedLeaf(renderedLeafId, projection.text.length) }
            .sortedBy(SpeakableTextProjectionSpan::spokenStart)
    val mapped =
        if (spans.isEmpty()) {
            null
        } else {
            directProjectionSpanMapping(spans, projection.text, renderedLeafId, renderedText)
                ?: alignedProjectionSpanMapping(spans, projection.text, renderedText)
        }
    return mapped.also { leafSpanCache?.put(cacheKey, it) }
}

@Suppress("ReturnCount")
private fun SpeakableTextProjectionSpan.isValidForRenderedLeaf(
    renderedLeafId: String,
    projectionTextLength: Int,
): Boolean {
    if (!leafId.belongsToRenderedLeaf(renderedLeafId)) return false
    if (spokenStart < 0) return false
    if (spokenEnd <= spokenStart) return false
    return spokenEnd <= projectionTextLength
}

private fun directProjectionSpanMapping(
    spans: List<SpeakableTextProjectionSpan>,
    projectedText: String,
    renderedLeafId: String,
    renderedText: String,
): List<RenderedProjectionSpan>? {
    val direct = ArrayList<RenderedProjectionSpan>(spans.size)
    var cursor = 0
    for (span in spans) {
        val segment = projectedText.substring(span.spokenStart, span.spokenEnd)
        if (!span.matchesDirectRenderedSegment(renderedLeafId, renderedText, segment, cursor)) return null
        direct += RenderedProjectionSpan(span, span.visibleStart)
        cursor = span.visibleEnd
    }
    return direct
}

@Suppress("ReturnCount")
private fun SpeakableTextProjectionSpan.matchesDirectRenderedSegment(
    renderedLeafId: String,
    renderedText: String,
    segment: String,
    cursor: Int,
): Boolean {
    if (leafId != renderedLeafId) return false
    if (visibleStart < cursor) return false
    if (visibleEnd - visibleStart != segment.length) return false
    if (visibleEnd > renderedText.length) return false
    return renderedText.regionMatches(visibleStart, segment, 0, segment.length)
}

@Suppress("ReturnCount")
private fun alignedProjectionSpanMapping(
    spans: List<SpeakableTextProjectionSpan>,
    projectedText: String,
    renderedText: String,
): List<RenderedProjectionSpan>? {
    val segments = spans.map { projectedText.substring(it.spokenStart, it.spokenEnd) }
    val earliest = IntArray(spans.size)
    var cursor = 0
    for (index in segments.indices) {
        val start = renderedText.indexOf(segments[index], startIndex = cursor)
        if (start < 0) return null
        earliest[index] = start
        cursor = start + segments[index].length
    }

    val latest = IntArray(spans.size)
    cursor = renderedText.length
    for (index in segments.indices.reversed()) {
        val segment = segments[index]
        val latestStart = cursor - segment.length
        if (latestStart < 0) return null
        val start = renderedText.lastIndexOf(segment, startIndex = latestStart)
        if (start < 0 || start + segment.length > cursor) return null
        latest[index] = start
        cursor = start
    }
    if (earliest.indices.any { earliest[it] != latest[it] }) return null

    return spans.indices.map { index ->
        RenderedProjectionSpan(
            source = spans[index],
            renderedStart = earliest[index],
        )
    }
}

@Suppress("ReturnCount")
private fun visibleWordHighlight(
    visibleWord: List<TtsVisibleTextSpan>,
    renderedLeafId: String,
    mappedSpans: List<RenderedProjectionSpan>,
): IntRange? {
    val intervals = ArrayList<RenderedInterval>()
    for (visibleSpan in visibleWord) {
        if (!visibleSpan.leafId.belongsToRenderedLeaf(renderedLeafId) || visibleSpan.start >= visibleSpan.end) {
            return null
        }
        var visibleCursor = visibleSpan.start
        val pieces =
            mappedSpans.filter { mapped ->
                mapped.source.leafId == visibleSpan.leafId &&
                    mapped.source.visibleEnd > visibleSpan.start &&
                    mapped.source.visibleStart < visibleSpan.end
            }
        for (mapped in pieces) {
            val overlapStart = max(visibleSpan.start, mapped.source.visibleStart)
            val overlapEnd = min(visibleSpan.end, mapped.source.visibleEnd)
            if (overlapStart != visibleCursor) return null
            intervals +=
                RenderedInterval(
                    start = mapped.renderedStart + overlapStart - mapped.source.visibleStart,
                    end = mapped.renderedStart + overlapEnd - mapped.source.visibleStart,
                )
            visibleCursor = overlapEnd
        }
        if (visibleCursor != visibleSpan.end) return null
    }
    return contiguousRange(intervals)
}

private data class SentenceSourceInterval(
    val start: Int,
    val end: Int,
)

private typealias SentenceCoverage = Set<TtsSentenceProjectionSegment>

private fun sentenceSourceInterval(
    sentenceIndex: Int,
    sentenceChunks: List<TtsChunk>,
): SentenceSourceInterval? {
    val chunks = sentenceChunks.filter { it.sentenceIndex == sentenceIndex }
    if (chunks.isEmpty()) return null
    return SentenceSourceInterval(
        start = chunks.minOf(TtsChunk::sourceStart),
        end = chunks.maxOf(TtsChunk::sourceEnd),
    )
}

private fun SpeakableTextProjection.sentenceCoverage(sentence: SentenceSourceInterval): SentenceCoverage =
    spans
        .mapNotNull { span -> span.sentenceCoverageSegment(sentence, text.length) }
        .toSet()

private fun List<RenderedProjectionSpan>.sentenceCoverage(sentence: SentenceSourceInterval): SentenceCoverage =
    mapNotNull { mapped -> mapped.source.sentenceCoverageSegment(sentence, Int.MAX_VALUE) }
        .toSet()

private fun SpeakableTextProjectionSpan.sentenceCoverageSegment(
    sentence: SentenceSourceInterval,
    projectionTextLength: Int,
): TtsSentenceProjectionSegment? {
    if (spokenStart < 0 || spokenEnd <= spokenStart || spokenEnd > projectionTextLength) return null
    val overlapStart = max(sentence.start, spokenStart)
    val overlapEnd = min(sentence.end, spokenEnd)
    return if (overlapStart < overlapEnd) {
        TtsSentenceProjectionSegment(leafId, overlapStart, overlapEnd)
    } else {
        null
    }
}

private fun List<RenderedProjectionSpan>.sentenceRenderedRanges(sentence: SentenceSourceInterval): List<IntRange> {
    val intervals =
        mapNotNull { mapped ->
            val overlapStart = max(sentence.start, mapped.source.spokenStart)
            val overlapEnd = min(sentence.end, mapped.source.spokenEnd)
            if (overlapStart >= overlapEnd) {
                null
            } else {
                RenderedInterval(
                    start = mapped.renderedStart + overlapStart - mapped.source.spokenStart,
                    end = mapped.renderedStart + overlapEnd - mapped.source.spokenStart,
                )
            }
        }.sortedBy(RenderedInterval::start)
    if (intervals.isEmpty()) return emptyList()
    val merged = ArrayList<RenderedInterval>()
    for (interval in intervals) {
        val previous = merged.lastOrNull()
        if (previous != null && interval.start <= previous.end) {
            merged[merged.lastIndex] = previous.copy(end = max(previous.end, interval.end))
        } else {
            merged += interval
        }
    }
    return merged.map { it.start until it.end }
}

private fun contiguousRange(intervals: List<RenderedInterval>): IntRange? {
    val ordered = intervals.sortedBy(RenderedInterval::start)
    val first = ordered.firstOrNull() ?: return null
    var valid = first.start >= 0 && first.start < first.end
    var end = first.end
    for (interval in ordered.drop(1)) {
        if (interval.start != end || interval.start >= interval.end) {
            valid = false
            break
        }
        end = interval.end
    }
    return if (valid) first.start until end else null
}

private fun String.belongsToRenderedLeaf(renderedLeafId: String): Boolean {
    val prefix = "$renderedLeafId/"
    return this == renderedLeafId || startsWith(prefix)
}

private fun projectionSentenceChunks(
    projection: SpeakableTextProjection,
    prepared: PreparedSpeechMessage,
): List<TtsChunk> =
    prepared.sentences.flatMap { sentence ->
        sentence.utterance.originRuns.flatMap { it.sources }.distinct().flatMap { source ->
            projection.spans.mapNotNull { span ->
                if (span.leafId != source.leafId) return@mapNotNull null
                val start = maxOf(source.start, span.visibleStart)
                val end = minOf(source.end, span.visibleEnd)
                if (start >= end) return@mapNotNull null
                TtsChunk(
                    "",
                    sentence.ordinal,
                    sentenceIndex = sentence.ordinal,
                    sourceStart = span.spokenStart + start - span.visibleStart,
                    sourceEnd = span.spokenStart + end - span.visibleStart,
                )
            }
        }
    }

/** Keep reused headers and their row separate from every intervening row. */
private fun sentenceProjectionIntervals(
    ordinal: Int,
    chunks: List<TtsChunk>,
    projection: SpeakableTextProjection,
    prepared: dev.ipf.whitenoise.android.audio.tts.speech.PreparedSpeechMessage?,
): List<SentenceSourceInterval> {
    if (prepared == null) return listOfNotNull(sentenceSourceInterval(ordinal, chunks))
    return prepared.sentences
        .firstOrNull { it.ordinal == ordinal }
        ?.let { sentence ->
            preparedProjectionIntervals(ordinal, chunks, projection, sentence)
        }.orEmpty()
}

private fun preparedProjectionIntervals(
    ordinal: Int,
    chunks: List<TtsChunk>,
    projection: SpeakableTextProjection,
    sentence: dev.ipf.whitenoise.android.audio.tts.speech.PreparedSentence,
): List<SentenceSourceInterval> {
    val sources =
        sentence.utterance.originRuns
            .flatMap { it.sources }
            .groupBy { it.leafId }
    val sentenceChunks = chunks.filter { it.sentenceIndex == ordinal }
    val firstSpoken = sentenceChunks.minOfOrNull { it.sourceStart } ?: return emptyList()
    val lastSpoken = sentenceChunks.maxOf { it.sourceEnd }
    val intervals =
        projection.spans
            .mapNotNull { span ->
                val leaf = sources[span.leafId] ?: return@mapNotNull null
                val start = maxOf(span.visibleStart, leaf.minOf { it.start })
                val end = minOf(span.visibleEnd, leaf.maxOf { it.end })
                if (start >= end) {
                    null
                } else {
                    var spokenStart = span.spokenStart + start - span.visibleStart
                    var spokenEnd = span.spokenStart + end - span.visibleStart
                    while (spokenStart > maxOf(span.spokenStart, firstSpoken) &&
                        projection.text[spokenStart - 1].isWhitespace()
                    ) {
                        spokenStart--
                    }
                    while (spokenEnd < minOf(span.spokenEnd, lastSpoken) &&
                        projection.text[spokenEnd].isWhitespace()
                    ) {
                        spokenEnd++
                    }
                    SentenceSourceInterval(spokenStart, spokenEnd)
                }
            }.sortedBy { it.start }
    val merged = mutableListOf<SentenceSourceInterval>()
    for (interval in intervals) {
        val last = merged.lastOrNull()
        if (last != null &&
            interval.start <= last.end
        ) {
            merged[merged.lastIndex] = last.copy(end = maxOf(last.end, interval.end))
        } else {
            merged +=
                interval
        }
    }
    return merged
}

private const val TTS_SENTENCE_EXCERPT_LENGTH = 160

private fun isOmittedHit(
    hit: RenderedTextHit,
    candidates: List<RenderedProjectionSpan>,
): Boolean {
    val mapped =
        candidates.any {
            val end = it.renderedStart + it.source.spokenEnd - it.source.spokenStart
            hit.renderedOffset >= it.renderedStart && hit.renderedOffset < end
        }
    val character = hit.renderedText.getOrNull(hit.renderedOffset)
    val content = character != null && !character.isWhitespace() && character !in ".!?,;:"
    val link = Regex("https?://\\S+").findAll(hit.renderedText).any { hit.renderedOffset in it.range }
    return !mapped && (content || link)
}

private const val TTS_SENTENCE_CHOICE_LIMIT = 200
