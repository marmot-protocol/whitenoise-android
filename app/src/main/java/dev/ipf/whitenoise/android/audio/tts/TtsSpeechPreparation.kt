package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSentence
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSpeechMessage
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechContext
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechMappingKind
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechPreparation
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechPreparationRequest
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechPreparationResult
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechRole
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechSourceRun
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechSourceSpan
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechStructure
import dev.ipf.whitenoise.android.audio.tts.speech.SpokenOriginRun

/** Prepare the original projected payload once, then compose its mapping with rendered leaves. */
internal fun TtsSpeakableEntry.prepareSpeech(
    context: SpeechContext,
    isCancelled: () -> Boolean = { false },
): PreparedSpeechMessage? {
    val runs = preparationRuns()
    val request = SpeechPreparationRequest(messageIdHex, projectionId, runs, context)
    val prepared = prepareBatches(request, isCancelled)?.let { joinBatches(it, isCancelled) } ?: return null
    return mapSentences(prepared, isCancelled)?.let { sentences ->
        prepared.copy(
            sentences = sentences,
            displayPreview = visibleLeaves.values.singleOrNull() ?: text,
            sourceRuns =
                if (visibleLeaves.isEmpty()) {
                    prepared.sourceRuns
                } else {
                    visibleLeaves.map { (leafId, original) ->
                        speechRoles[leafId] ?: SpeechSourceRun(leafId, original, SpeechRole.Prose)
                    }
                },
        )
    }
}

private fun TtsSpeakableEntry.mapSentences(
    prepared: PreparedSpeechMessage,
    isCancelled: () -> Boolean,
): List<PreparedSentence>? =
    prepared.sentences.map { sentence ->
        if (isCancelled()) return null
        sentence.copy(
            utterance =
                sentence.utterance.copy(
                    originRuns =
                        sentence.utterance.originRuns.flatMap { run ->
                            mapOrigin(run)
                        },
                ),
        )
    }

private fun TtsSpeakableEntry.preparationRuns(): List<SpeechSourceRun> {
    val runs = mutableListOf<SpeechSourceRun>()
    val groups = preparationGroups()
    var cursor = 0

    fun prose(
        start: Int,
        end: Int,
    ) {
        val part = text.substring(start, end)
        if (part.any { !it.isWhitespace() && it !in ".!?" }) {
            runs +=
                SpeechSourceRun("projection:$start", part, SpeechRole.Prose)
        }
    }
    for (spans in groups) {
        val start = spans.minOf { it.spoken.start }
        val end = spans.maxOf { it.spoken.end }
        if (cursor < start) prose(cursor, start)
        runs += speechRoles[spans.first().visible.leafId] ?: tableRun(spans, start, end)
        cursor = end
    }
    if (cursor < text.length) prose(cursor, text.length)
    return runs
}

private fun prepareBatches(
    request: SpeechPreparationRequest,
    isCancelled: () -> Boolean,
): List<PreparedSpeechMessage>? {
    var result = SpeechPreparation.prepare(request, isCancelled)
    val batches = mutableListOf<PreparedSpeechMessage>()
    while (true) {
        when (val batch = result) {
            is SpeechPreparationResult.Unavailable -> return null
            is SpeechPreparationResult.Complete -> {
                batches += batch.message
                break
            }
            is SpeechPreparationResult.Continuation -> {
                batches += batch.message
                result = SpeechPreparation.prepare(request.copy(cursor = batch.cursor), isCancelled)
            }
        }
    }
    return batches
}

private fun joinBatches(
    batches: List<PreparedSpeechMessage>,
    isCancelled: () -> Boolean,
): PreparedSpeechMessage? {
    val joined = mutableListOf<PreparedSentence>()
    for (sentence in batches.flatMap { it.sentences }) {
        if (isCancelled()) return null
        val previous = joined.lastOrNull()
        if (previous?.sentenceId == sentence.sentenceId) {
            val offset = previous.utterance.engineText.length
            joined[joined.lastIndex] =
                previous.copy(
                    utterance =
                        previous.utterance.copy(
                            engineText = previous.utterance.engineText + sentence.utterance.engineText,
                            originRuns =
                                previous.utterance.originRuns +
                                    sentence.utterance.originRuns.map { run ->
                                        run.copy(
                                            spoken =
                                                TtsTextRange(
                                                    run.spoken.start + offset,
                                                    run.spoken.end + offset,
                                                ),
                                        )
                                    },
                        ),
                )
        } else {
            joined += sentence
        }
    }
    val prepared = batches.first().copy(sentences = joined)
    return prepared
}

private fun TtsSpeakableEntry.mapOrigin(run: SpokenOriginRun): List<SpokenOriginRun> {
    if (run.kind == SpeechMappingKind.Synthetic || run.sources.all { it.leafId in speechRoles }) return listOf(run)
    val resultRuns = mutableListOf<SpokenOriginRun>()
    for (localSource in run.sources) {
        val offset = localSource.leafId.substringAfter(":").toIntOrNull() ?: 0
        val source = localSource.copy(start = localSource.start + offset, end = localSource.end + offset)
        val overlaps =
            spokenTextSpans.mapNotNull { span ->
                val start = maxOf(source.start, span.spoken.start)
                val end = minOf(source.end, span.spoken.end)
                if (start >= end) null else Triple(span, start, end)
            }
        if (run.kind == SpeechMappingKind.Replacement) {
            val visible =
                overlaps.map { (span, start, end) ->
                    SpeechSourceSpan(
                        span.visible.leafId,
                        span.visible.start + start - span.spoken.start,
                        span.visible.start + end - span.spoken.start,
                    )
                }
            if (visible.isNotEmpty()) resultRuns += run.copy(sources = visible)
        } else {
            overlaps.forEach { (span, start, end) ->
                resultRuns +=
                    SpokenOriginRun(
                        TtsTextRange(run.spoken.start + start - source.start, run.spoken.start + end - source.start),
                        listOf(
                            SpeechSourceSpan(
                                span.visible.leafId,
                                span.visible.start + start - span.spoken.start,
                                span.visible.start + end - span.spoken.start,
                            ),
                        ),
                        SpeechMappingKind.Identity,
                    )
            }
        }
    }
    return resultRuns
}

private val tablePath = Regex("^(.*)/(h([0-9]+)|r([0-9]+)/c([0-9]+))(?:/.*)?$")

private fun TtsSpeakableEntry.preparationGroups(): List<List<TtsSpokenTextSpan>> {
    val groups =
        spokenTextSpans
            .filter { it.visible.leafId in speechRoles || tablePath.matches(it.visible.leafId) }
            .groupBy { span ->
                if (span.visible.leafId in
                    speechRoles
                ) {
                    span.visible.leafId
                } else {
                    tablePath.matchEntire(span.visible.leafId)!!.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
                }
            }.values
            .sortedBy { spans -> spans.minOf { it.spoken.start } }
    return groups
}

// Indices select the fixed table-path regex captures.
@Suppress("MagicNumber")
private fun TtsSpeakableEntry.tableRun(
    spans: List<TtsSpokenTextSpan>,
    start: Int,
    end: Int,
): SpeechSourceRun {
    val match = tablePath.matchEntire(spans.first().visible.leafId)!!
    val structure =
        if (match.groupValues[3].isNotEmpty()) {
            SpeechStructure.TableHeader(match.groupValues[3].toInt())
        } else {
            SpeechStructure.TableCell(match.groupValues[4].toInt(), match.groupValues[5].toInt())
        }
    return SpeechSourceRun("projection:$start", text.substring(start, end), SpeechRole.Prose, structure = structure)
}
