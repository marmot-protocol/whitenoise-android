package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDictationRecognitionCoordinatorTest {
    /** Four accepted ten-second spans followed by the reported quiet tail replay only the fourth. */
    @Test
    fun reportedTailReplacesWholeLastSpanWithoutDroppingQuietSamples() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 27L)
        val recognition = ConversationDictationRecognitionCoordinator()
        repeat(4) { index ->
            val pcm = ByteArray(320_000) { (index + 1).toByte() }
            assertTrue(buffer.append(pcm, pcm.size, hasSpeech = true))
            assertTrue(buffer.sealCurrentIfAtLeast(320_000))
            val request = checkNotNull(buffer.requests.pollRequest())
            assertEquals(index * 160_000L, request.chunk.firstSample)
            assertTrue(recognition.canAccept(request))
            assertTrue(recognition.accept(checkNotNull(buffer.requests.acknowledge(request, "part ${index + 1}"))))
            assertEquals(320_000, buffer.bufferedBytes)
        }
        val tail = ByteArray(93_436) { 1 } // PCM16 amplitude below the endpoint threshold.
        assertTrue(buffer.append(tail, tail.size, hasSpeech = true))
        buffer.finish()
        val rejected = checkNotNull(buffer.requests.pollRequest())
        assertEquals(640_000L, rejected.chunk.firstSample)
        assertEquals(686_718L, rejected.chunk.lastSampleExclusive)
        assertTrue(buffer.requests.retryWithContext(rejected))
        val contextual = checkNotNull(buffer.requests.pollRequest())
        assertEquals(listOf(320_000, 93_436), contextual.pcmParts.map { it.pcm.size })
        assertArrayEquals(tail, contextual.chunk.pcm)
        assertEquals(413_436, buffer.bufferedBytes)
        assertTrue(buffer.hasPending)
        assertTrue(recognition.canAccept(contextual))
        val settlement = checkNotNull(buffer.requests.acknowledge(contextual, "part four, after the bridge"))
        assertTrue(recognition.accept(settlement))
        assertEquals("part 1 part 2 part 3 part four, after the bridge", recognition.transcript)
        assertFalse(buffer.hasPending)
        assertEquals(413_436, buffer.bufferedBytes)
        assertNull(buffer.requests.acknowledge(contextual, "duplicate"))
        buffer.discard()
        assertArrayEquals(ByteArray(320_000), contextual.replayParts.single().pcm)
        assertArrayEquals(ByteArray(93_436), contextual.chunk.pcm)
    }

    /** Exact unchanged replay retains the old span and releases each new quiet window. */
    @Test
    fun unchangedContextKeepsExplicitNoncontiguousProvenanceWithinMemoryLimit() {
        val buffer =
            ConversationDictationAudioChunkBuffer(
                sessionId = 1L, chunkBytes = 8, requestBytes = 16, maxBufferedBytes = 24,
            )
        val recognition = ConversationDictationRecognitionCoordinator()
        val anchorPcm = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        assertTrue(buffer.append(anchorPcm, 8, hasSpeech = true))
        val first = checkNotNull(buffer.requests.pollRequest())
        assertTrue(recognition.accept(checkNotNull(buffer.requests.acknowledge(first, "turn left"))))
        repeat(8) { window ->
            val quiet = byteArrayOf((window + 5).toByte(), 0, 0, 0)
            assertTrue(buffer.append(quiet, 4, hasSpeech = true))
            assertTrue(buffer.sealCurrentIfAtLeast(4))
            val rejected = checkNotNull(buffer.requests.pollRequest())
            assertTrue(buffer.requests.retryWithContext(rejected))
            val replay = checkNotNull(buffer.requests.pollRequest())
            assertEquals(listOf(0L, rejected.chunk.firstSample), replay.pcmParts.map { it.firstSample })
            assertEquals(12, replay.pcmParts.sumOf { it.pcm.size })
            assertTrue(recognition.accept(checkNotNull(buffer.requests.acknowledge(replay, "turn left"))))
            assertEquals(8, buffer.bufferedBytes)
            assertFalse(buffer.hasPending)
            assertArrayEquals(ByteArray(4), replay.chunk.pcm)
        }
        assertEquals("turn left", recognition.transcript)
        buffer.discard()
        assertArrayEquals(ByteArray(8), first.chunk.pcm)
        assertEquals(0, buffer.bufferedBytes)
    }

    /** A one-sample request overflow cannot misrepresent an anchor slice as a full transcript. */
    @Test
    fun requestOverflowKeepsUnresolvedChunkAndExistingTranscript() {
        val buffer =
            ConversationDictationAudioChunkBuffer(
                sessionId = 1L, chunkBytes = 10, requestBytes = 16, maxBufferedBytes = 24,
            )
        val recognition = ConversationDictationRecognitionCoordinator()
        assertTrue(buffer.append(ByteArray(10) { 1 }, 10, hasSpeech = true))
        val first = checkNotNull(buffer.requests.pollRequest())
        assertTrue(recognition.accept(checkNotNull(buffer.requests.acknowledge(first, "hello"))))
        assertTrue(buffer.append(ByteArray(8) { 2 }, 8, hasSpeech = true))
        buffer.finish()
        val rejected = checkNotNull(buffer.requests.pollRequest())
        assertFalse(buffer.requests.retryWithContext(rejected))
        assertEquals(18, buffer.bufferedBytes)
        assertTrue(buffer.hasPending)
        assertEquals("hello", recognition.transcript)
        assertFalse(recognition.canAccept(ConversationDictationAudioRequest(rejected.chunk, listOf(first.chunk), 999L)))
        buffer.discard()
        assertArrayEquals(ByteArray(10), first.chunk.pcm)
        assertArrayEquals(ByteArray(8), rejected.chunk.pcm)
    }

    /** Changed recognition rewrites words and punctuation without lexical overlap guesses. */
    @Test
    fun contextualCorrectionsAndRepeatedWordsReplaceOnlyTheirOwnedSpan() {
        val recognition = ConversationDictationRecognitionCoordinator()
        recognition.acceptProviderMicrophone("go")
        assertTrue(recognition.accept(ConversationDictationAudioSettlement(7, null, "left left")))
        assertTrue(recognition.accept(ConversationDictationAudioSettlement(8, 7, "left, left!")))
        assertEquals("go left, left!", recognition.transcript)
        assertFalse(recognition.accept(ConversationDictationAudioSettlement(9, 7, "late")))
        assertEquals("go left, left!", recognition.transcript)
    }
}
