package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant: a failing final caller-audio chunk after Send or Paste is released only on bounded evidence
 * that it holds no unrecognized speech, so recognized text is delivered instead of parked in the composer.
 * Earlier chunks, long or unknown-length tails with transient faults, and first faults keep the retry path.
 */
class ConversationDictationFinalTailCoverageTest {
    /** Only the final chunk after recognized speech may be skipped, and only for weak evidence of speech. */
    @Test
    fun finalTailDropPolicyKeepsLongSpeechAndEarlierChunksOnRetryPath() {
        val recognition = ConversationDictationRecognitionCoordinator()
        val short = ConversationDictationRecognitionCoordinator.SHORT_FINAL_TAIL_MILLIS
        val long = short + 1L

        fun drop(
            fault: ConversationDictationRecognitionCoordinator.TailFault,
            millis: Long?,
            hasSpeech: Boolean? = true,
            finalChunk: Boolean = true,
        ) = recognition.shouldDropFinalTail(
            ConversationDictationRecognitionCoordinator.TailInput(
                chunkId = 4L,
                finalChunk = finalChunk,
                chunkHasSpeech = hasSpeech,
                tailMillis = millis,
                fault = fault,
            ),
        )
        val empty = ConversationDictationRecognitionCoordinator.TailFault.Empty
        val transient = ConversationDictationRecognitionCoordinator.TailFault.Transient
        val timedOut = ConversationDictationRecognitionCoordinator.TailFault.TimedOut

        for (fault in ConversationDictationRecognitionCoordinator.TailFault.entries) {
            assertFalse(drop(fault, millis = 100L, hasSpeech = false, finalChunk = false))
        }
        assertTrue(drop(empty, millis = long, hasSpeech = false))
        assertFalse(drop(empty, millis = 100L))
        assertFalse(drop(empty, millis = null, hasSpeech = null))
        assertFalse(drop(transient, millis = short))
        assertTrue(drop(timedOut, millis = short))
        assertFalse(drop(timedOut, millis = long))
        assertFalse(drop(timedOut, millis = null))

        recognition.recordFailure(chunkId = 3L, version = "a", capacity = false)
        assertFalse(drop(empty, millis = long))

        recognition.recordFailure(chunkId = 4L, version = "a", capacity = false)
        assertTrue(drop(empty, millis = long))
        assertTrue(drop(transient, millis = short))
        assertFalse(drop(transient, millis = long))
        assertFalse(drop(transient, millis = null))
    }
}
