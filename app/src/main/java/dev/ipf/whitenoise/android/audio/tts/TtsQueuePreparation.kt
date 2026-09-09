package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.audio.tts.speech.SpeechContext
import java.util.Locale

/** Pure projections created for the current request, with no retained protocol data. */
internal class TtsQueuePreparation(
    private val maxChunkLength: Int,
) {
    fun List<TtsSpeakableEntry>.toQueuedMessages(
        locale: Locale,
        isCancelled: () -> Boolean = { false },
    ): List<TtsQueuedMessage> =
        boundedSpeakableEntries(this).map {
            if (isCancelled()) {
                return emptyList()
            } else {
                it.toQueuedMessage(locale, isCancelled)
                    ?: return emptyList()
            }
        }

    fun TtsSpeakableEntry.toQueuedMessage(
        locale: Locale,
        isCancelled: () -> Boolean = { false },
    ): TtsQueuedMessage? {
        // Ad-hoc engine previews have no rendered projection to transform.
        return when {
            spokenTextSpans.isEmpty() && speechRoles.isEmpty() -> legacyQueuedMessage(locale)
            senderAnnouncementReserve(senderDisplayName.trim()) >= maxChunkLength -> null
            else ->
                prepareSpeech(SpeechContext(voiceLocale = locale, mode = speechMode), isCancelled)?.let { prepared ->
                    preparedQueuedMessage(
                        prepared = prepared,
                        senderKey = senderKey,
                        senderDisplayName = senderDisplayName.trim(),
                        maxChunkLength =
                            (
                                maxChunkLength -
                                    senderAnnouncementReserve(
                                        senderDisplayName.trim(),
                                    )
                            ).coerceAtLeast(1),
                        timelineAt = timelineAt,
                    )?.let { queued ->
                        queued.copy(
                            preview = queued.preview.take(TTS_PREVIEW_MAX_LENGTH),
                            announcementsPrepared = false,
                        )
                    }
                }
        }
    }

    private fun TtsSpeakableEntry.legacyQueuedMessage(locale: Locale): TtsQueuedMessage? {
        val trimStart = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
        val trimEnd = text.indexOfLast { !it.isWhitespace() } + 1
        val trimmed = text.substring(trimStart, trimEnd)
        val announcementName = senderDisplayName.trim()
        val sentenceChunks =
            TtsChunker.chunk(
                text = trimmed,
                locale = locale,
                maxChunkLength = maxChunkLength,
                leadingChunkReserve = senderAnnouncementReserve(announcementName),
            )
        return sentenceChunks.takeIf { it.isNotEmpty() }?.let { chunks ->
            TtsQueuedMessage(
                senderKey = senderKey,
                senderDisplayName = announcementName,
                preview = trimmed.take(TTS_PREVIEW_MAX_LENGTH),
                // The queue reflattens indices itself — sentence identity must survive.
                chunks =
                    chunks.map { chunk ->
                        val sourceStart = trimStart + chunk.sourceStart
                        val sourceEnd = trimStart + chunk.sourceEnd
                        chunk.copy(
                            index = 0,
                            messageIdHex = messageIdHex,
                            projectionId = projectionId,
                            timelineAt = timelineAt,
                            visibleSpans = spokenTextSpans.forChunk(sourceStart, sourceEnd),
                        )
                    },
                messageIdHex = messageIdHex,
                projectionId = projectionId,
                timelineAt = timelineAt,
            )
        }
    }

    private fun List<TtsSpokenTextSpan>.forChunk(
        sourceStart: Int,
        sourceEnd: Int,
    ): List<TtsSpokenTextSpan> =
        mapNotNull { span ->
            val start = maxOf(sourceStart, span.spoken.start)
            val end = minOf(sourceEnd, span.spoken.end)
            if (start >= end) {
                null
            } else {
                val visibleStart = span.visible.start + (start - span.spoken.start)
                TtsSpokenTextSpan(
                    spoken = TtsTextRange(start - sourceStart, end - sourceStart),
                    visible =
                        TtsVisibleTextSpan(
                            leafId = span.visible.leafId,
                            start = visibleStart,
                            end = visibleStart + (end - start),
                        ),
                )
            }
        }

    private fun senderAnnouncementReserve(displayName: String): Int =
        displayName
            .takeIf(String::isNotEmpty)
            ?.let { "$it: ".length } ?: 0
}
