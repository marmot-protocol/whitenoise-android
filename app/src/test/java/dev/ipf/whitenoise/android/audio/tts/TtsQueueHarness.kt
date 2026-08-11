package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech

/** Recording wrapper around the real queue: no logic, only observation. */
internal class TtsQueueHarness(
    var enqueueResult: Int = TextToSpeech.SUCCESS,
) {
    val enqueued = mutableListOf<Pair<TtsChunk, String>>()
    var stopCalls = 0
        private set
    var terminalCalls = 0
        private set

    val queue =
        TtsPlaybackQueue(
            stopEngine = { stopCalls += 1 },
            enqueue = { chunk, utteranceId ->
                enqueued += chunk to utteranceId
                enqueueResult
            },
            onTerminal = { terminalCalls += 1 },
        )

    fun utteranceId(position: Int): String = enqueued[position].second

    fun spokenText(position: Int = 0): String = enqueued[position].first.text

    fun spokenTextLength(position: Int = 0): Int = spokenText(position).length

    fun lastSpokenTexts(count: Int): List<String> = enqueued.takeLast(count).map { it.first.text }
}

internal fun ttsMessage(
    senderKey: String,
    senderDisplayName: String,
    vararg sentences: String,
): TtsQueuedMessage =
    TtsQueuedMessage(
        senderKey = senderKey,
        senderDisplayName = senderDisplayName,
        preview = sentences.joinToString(separator = " "),
        chunks =
            sentences.mapIndexed { index, text ->
                TtsChunk(text = text, index = index, sentenceIndex = index)
            },
    )

internal fun ttsMessageWithChunks(
    senderKey: String,
    senderDisplayName: String,
    preview: String,
    chunks: List<Pair<String, Int>>,
): TtsQueuedMessage =
    TtsQueuedMessage(
        senderKey = senderKey,
        senderDisplayName = senderDisplayName,
        preview = preview,
        chunks =
            chunks.mapIndexed { index, (text, sentenceIndex) ->
                TtsChunk(text = text, index = index, sentenceIndex = sentenceIndex)
            },
    )

internal fun ttsMessages(vararg messages: TtsQueuedMessage): List<TtsQueuedMessage> = messages.toList()

internal fun ttsMessageWithId(
    messageIdHex: String,
    senderKey: String,
    senderDisplayName: String,
    vararg sentences: String,
): TtsQueuedMessage = ttsMessage(senderKey, senderDisplayName, *sentences).copy(messageIdHex = messageIdHex)
