package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.audio.tts.speech.PreparedChunkingResult
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedEngineChunker
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSpeechMessage
import java.util.Locale

internal fun preparedQueuedMessage(
    prepared: PreparedSpeechMessage,
    senderKey: String,
    senderDisplayName: String,
    maxChunkLength: Int,
    timelineAt: ULong = 0uL,
): TtsQueuedMessage? {
    val result = PreparedEngineChunker.chunk(prepared, maxChunkLength) as? PreparedChunkingResult.Chunks
    if (result == null || result.chunks.isEmpty()) return null
    val chunks =
        result.chunks.mapIndexed { index, chunk ->
            val prefixLength = chunk.senderPrefix?.end ?: 0
            TtsChunk(
                text = chunk.text,
                index = index,
                sentenceIndex = chunk.sentenceOrdinal,
                sourceStart = chunk.utteranceOffset + prefixLength,
                sourceEnd =
                    chunk.utteranceOffset + chunk.text.length,
                sourceText = chunk.utterance.engineText,
                messageIdHex = prepared.messageIdHex,
                projectionId = prepared.provenance.sourceRevisionId,
                timelineAt = timelineAt,
                visibleSpans =
                    chunk.originRuns.flatMap { run ->
                        var cursor = run.spoken.start
                        run.sources.map { span ->
                            val spoken =
                                if (run.kind ==
                                    dev.ipf.whitenoise.android.audio.tts.speech.SpeechMappingKind.Identity
                                ) {
                                    TtsTextRange(cursor, cursor + span.end - span.start)
                                } else {
                                    run.spoken
                                }
                            cursor += span.end - span.start
                            TtsSpokenTextSpan(spoken, TtsVisibleTextSpan(span.leafId, span.start, span.end), run.kind)
                        }
                    },
                senderPrefix = chunk.senderPrefix,
                locale = Locale.forLanguageTag(prepared.provenance.effectiveLanguageTag),
            )
        }
    return TtsQueuedMessage(
        senderKey,
        senderDisplayName,
        prepared.displayPreview,
        chunks,
        prepared.messageIdHex,
        prepared.provenance.sourceRevisionId,
        timelineAt,
        announcementsPrepared = true,
        prepared = prepared,
    )
}
