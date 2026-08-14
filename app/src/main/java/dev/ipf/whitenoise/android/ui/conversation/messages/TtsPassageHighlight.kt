@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.messages

import android.speech.tts.TextToSpeech
import dev.ipf.whitenoise.android.audio.tts.TtsChunk
import dev.ipf.whitenoise.android.audio.tts.TtsChunker
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.SpeakableTextProjectionSpan
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
    locale: Locale,
): (String, String) -> IntRange? =
    TtsHighlightProjectionResolver(
        projection = projection,
        locale = locale,
    ).resolverFor(passage, messageIdHex)

/** Projection-level work shared by every word update in one active message. */
internal class TtsHighlightProjectionResolver(
    private val projection: SpeakableTextProjection,
    locale: Locale,
) {
    private val sentenceChunks =
        TtsChunker.chunk(
            projection.text,
            locale,
            maxChunkLength = ttsHighlightMaxChunkLength(),
        )
    private val leafSpanCache = HashMap<Pair<String, String>, List<RenderedProjectionSpan>?>()

    internal val cachedLeafCount: Int
        get() = leafSpanCache.size

    internal fun resolverFor(
        passage: TtsPassage,
        messageIdHex: String,
    ): (String, String) -> IntRange? =
        { renderedLeafId, renderedText ->
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = messageIdHex,
                projection = projection,
                renderedLeafId = renderedLeafId,
                renderedText = renderedText,
                sentenceChunks = sentenceChunks,
                leafSpanCache = leafSpanCache,
            )
        }
}

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
    val sentenceChunks =
        TtsChunker.chunk(
            projection.text,
            locale,
            maxChunkLength = ttsHighlightMaxChunkLength(),
        )
    return resolveTtsRenderedHighlight(
        passage = passage,
        messageIdHex = messageIdHex,
        projection = projection,
        renderedLeafId = renderedLeafId,
        renderedText = renderedText,
        sentenceChunks = sentenceChunks,
        leafSpanCache = null,
    )
}

@Suppress("ReturnCount")
private fun resolveTtsRenderedHighlight(
    passage: TtsPassage?,
    messageIdHex: String,
    projection: SpeakableTextProjection,
    renderedLeafId: String,
    renderedText: String,
    sentenceChunks: List<TtsChunk>,
    leafSpanCache: MutableMap<Pair<String, String>, List<RenderedProjectionSpan>?>?,
): IntRange? {
    if (passage == null || passage.messageIdHex != messageIdHex) return null
    if (passage.projectionId != projection.projectionId) return null
    if (renderedText.isEmpty()) return null
    val cacheKey = renderedLeafId to renderedText
    val mappedSpans =
        if (leafSpanCache != null && leafSpanCache.containsKey(cacheKey)) {
            leafSpanCache[cacheKey]
        } else {
            mapProjectionSpansToRenderedLeaf(
                projection = projection,
                renderedLeafId = renderedLeafId,
                renderedText = renderedText,
            ).also { leafSpanCache?.put(cacheKey, it) }
        }
    if (mappedSpans == null) return null
    return if (passage.visibleWord.isNotEmpty()) {
        visibleWordHighlight(passage.visibleWord, renderedLeafId, mappedSpans)
    } else {
        sentenceHighlight(passage.sentenceIndex, mappedSpans, sentenceChunks)
    }
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
): List<RenderedProjectionSpan>? {
    val spans =
        projection.spans
            .filter { it.isValidForRenderedLeaf(renderedLeafId, projection.text.length) }
            .sortedBy(SpeakableTextProjectionSpan::spokenStart)
    if (spans.isEmpty()) return null
    return directProjectionSpanMapping(spans, projection.text, renderedLeafId, renderedText)
        ?: alignedProjectionSpanMapping(spans, projection.text, renderedText)
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

@Suppress("ReturnCount")
private fun sentenceHighlight(
    sentenceIndex: Int,
    mappedSpans: List<RenderedProjectionSpan>,
    sentenceChunks: List<TtsChunk>,
): IntRange? {
    val sentence = sentenceChunks.firstOrNull { it.sentenceIndex == sentenceIndex } ?: return null
    val intervals = ArrayList<RenderedInterval>()
    for (mapped in mappedSpans) {
        val overlapStart = max(sentence.sourceStart, mapped.source.spokenStart)
        val overlapEnd = min(sentence.sourceEnd, mapped.source.spokenEnd)
        if (overlapStart >= overlapEnd) continue
        intervals +=
            RenderedInterval(
                start = mapped.renderedStart + overlapStart - mapped.source.spokenStart,
                end = mapped.renderedStart + overlapEnd - mapped.source.spokenStart,
            )
    }
    return contiguousRange(intervals)
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
