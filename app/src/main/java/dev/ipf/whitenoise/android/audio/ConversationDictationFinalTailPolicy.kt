package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionCoordinator.Companion.SHORT_FINAL_TAIL_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionCoordinator.TailFault
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionCoordinator.TailInput

/**
 * Decides whether a failing final chunk may be skipped so recognized text can still be delivered.
 *
 * The audio after the last recognized sentence is usually breath, room noise or the Send tap.
 * Holding the whole message for it turns a successful dictation into a draft. Earlier chunks,
 * long tails with real speech and first transient faults still take the bounded retry path.
 */
internal fun ConversationDictationRecognitionCoordinator.shouldDropFinalTail(input: TailInput): Boolean {
    if (!input.finalChunk) return false
    val priorFailures = if (failureChunkId == input.chunkId) failureAttempts else 0
    val shortTail = input.tailMillis != null && input.tailMillis <= SHORT_FINAL_TAIL_MILLIS
    return when (input.fault) {
        TailFault.Empty -> input.chunkHasSpeech == false || priorFailures >= 1
        TailFault.Transient -> shortTail && priorFailures >= 1
        TailFault.TimedOut -> shortTail
    }
}
