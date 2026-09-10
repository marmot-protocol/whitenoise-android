package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDictationAudioChunkBufferTest {
    /** Verifies an hour of bounded capture preserves every ordered sample range across 120 acknowledgments. */
    @Test
    fun oneHourOfAcknowledgedAudioKeepsExactOrderedSampleRangesWithinTheBound() {
        val bytesPerSecond = CALLER_AUDIO_SAMPLE_RATE_HZ * 2
        val buffer =
            ConversationDictationAudioChunkBuffer(
                sessionId = 7L,
                chunkBytes = bytesPerSecond * 30,
                maxBufferedBytes = bytesPerSecond * 90,
            )
        var expectedSample = 0L
        var chunks = 0

        repeat(3_600) { second ->
            val pcm = ByteArray(bytesPerSecond) { (second and 0xff).toByte() }
            assertTrue(buffer.append(pcm, pcm.size))
            buffer.poll()?.let { chunk ->
                assertEquals(7L, chunk.sessionId)
                assertEquals(chunks.toLong() + 1L, chunk.chunkId)
                assertEquals(expectedSample, chunk.firstSample)
                assertEquals(expectedSample + chunk.pcm.size / 2, chunk.lastSampleExclusive)
                assertEquals((second - 29 and 0xff).toByte(), chunk.pcm.first())
                assertEquals((second and 0xff).toByte(), chunk.pcm.last())
                expectedSample = chunk.lastSampleExclusive
                chunks += 1
                buffer.acknowledge(chunk.chunkId)
            }
            assertTrue(buffer.bufferedBytes <= bytesPerSecond * 90)
        }
        buffer.finish()

        assertEquals(120, chunks)
        assertEquals(3_600L * CALLER_AUDIO_SAMPLE_RATE_HZ, expectedSample)
        assertEquals(0, buffer.bufferedBytes)
        assertFalse(buffer.hasPending)
    }

    /** A retry must preserve chunk identity, PCM, and byte accounting until successful acknowledgment. */
    @Test
    fun retryRequeuesTheSameChunkWithoutDuplicatingItsAccounting() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 11L, chunkBytes = 4, maxBufferedBytes = 8)
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4), 4))
        val first = checkNotNull(buffer.poll())

        assertTrue(buffer.retry(first.chunkId))
        val retried = checkNotNull(buffer.poll())

        assertEquals(first.chunkId, retried.chunkId)
        assertArrayEquals(first.pcm, retried.pcm)
        assertEquals(4, buffer.bufferedBytes)
        assertTrue(buffer.acknowledge(retried.chunkId))
        assertEquals(0, buffer.bufferedBytes)
        assertFalse(buffer.acknowledge(retried.chunkId))
    }

    /** A rejected read must leave earlier queued and partial PCM intact for recovery. */
    @Test
    fun overflowRejectsTheWholeWriteWithoutSilentlyChangingBufferedAudio() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 1L, chunkBytes = 4, maxBufferedBytes = 8)
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4, 5, 6), 6))

        assertFalse(buffer.append(byteArrayOf(7, 8, 9, 10), 4))
        buffer.finish()

        val first = checkNotNull(buffer.poll())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), first.pcm)
        assertTrue(buffer.acknowledge(first.chunkId))
        val second = checkNotNull(buffer.poll())
        assertArrayEquals(byteArrayOf(5, 6), second.pcm)
        assertTrue(buffer.acknowledge(second.chunkId))
        assertNull(buffer.poll())
        assertEquals(0, buffer.bufferedBytes)
    }

    /** Repeated stop requests must seal a partial tail once and reject further capture writes. */
    @Test
    fun finishSealsOnePartialChunkAndIsIdempotent() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 3L, chunkBytes = 8, maxBufferedBytes = 16)
        assertTrue(buffer.append(byteArrayOf(9, 8), 2))

        buffer.finish()
        buffer.finish()

        val chunk = checkNotNull(buffer.poll())
        assertArrayEquals(byteArrayOf(9, 8), chunk.pcm)
        assertEquals(0L, chunk.firstSample)
        assertEquals(1L, chunk.lastSampleExclusive)
        assertNull(buffer.poll())
        assertFalse(buffer.append(byteArrayOf(7, 6), 2))
    }

    /** Rejects odd byte counts that would split a PCM16 sample in configuration or input. */
    @Test
    fun pcm16AlignmentIsRequiredForConfigurationAndAppends() {
        assertFails<IllegalArgumentException> {
            ConversationDictationAudioChunkBuffer(sessionId = 1L, chunkBytes = 3, maxBufferedBytes = 6)
        }
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 1L, chunkBytes = 4, maxBufferedBytes = 8)

        assertFails<IllegalArgumentException> { buffer.append(byteArrayOf(1), 1) }
    }

    /** Prevents parallel provider requests from claiming different chunks out of sequence. */
    @Test
    fun onlyOneChunkCanBeInFlightAtATime() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 5L, chunkBytes = 4, maxBufferedBytes = 8)
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 8))

        val first = checkNotNull(buffer.poll())
        assertNull(buffer.poll())
        assertTrue(buffer.acknowledge(first.chunkId))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), checkNotNull(buffer.poll()).pcm)
    }

    /** Cancellation must clear queued, partial, and in-flight PCM and prevent further appends. */
    @Test
    fun discardClearsCurrentQueuedAndInFlightAudio() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 9L, chunkBytes = 4, maxBufferedBytes = 12)
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4, 5, 6), 6))
        checkNotNull(buffer.poll())

        buffer.discard()

        assertEquals(0, buffer.bufferedBytes)
        assertFalse(buffer.hasPending)
        assertNull(buffer.poll())
        assertFalse(buffer.append(byteArrayOf(7, 8), 2))
    }

    /** Checks the failure type and propagates unexpected exceptions instead of hiding test defects. */
    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
