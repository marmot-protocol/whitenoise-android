package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCallerAudioDrainTest {
    /** Recorder closure must not prevent serial streams from consuming the sealed tail. */
    @Test
    fun stoppedCaptureAllowsNewProviderStreamsUntilDiscarded() {
        val buffer = ConversationDictationAudioChunkBuffer(1L, chunkBytes = 4)
        buffer.append(byteArrayOf(1, 2, 3, 4, 5, 6), 6, hasSpeech = true)
        val capture = ConversationDictationCallerAudio(StoppedCaptureDevice, buffer)
        capture.finish {}
        try {
            val first = checkNotNull(capture.openProviderStream())
            assertTrue(capture.start())
            first.cancel()
            first.closeProviderEnd()
            val replacement = capture.openProviderStream()
            assertNotNull(replacement)
            replacement?.cancel()
            replacement?.closeProviderEnd()
        } finally {
            capture.discard()
        }
        assertFalse(capture.start())
        assertNull(capture.openProviderStream())
    }

    /** Full and partial chunks drain in order through real streams without restarting capture. */
    @Test
    fun streamsDrainFullChunkAndPartialTailWithoutRestartingRecorder() {
        val buffer = ConversationDictationAudioChunkBuffer(1L, chunkBytes = 4)
        buffer.append(byteArrayOf(1, 2, 3, 4, 5, 6), 6, hasSpeech = true)
        val bytes = mutableListOf<Byte>()
        val writer =
            ConversationDictationAudioPipeWriter { _, pcm, offset, length ->
                bytes.addAll(pcm.slice(offset until offset + length))
                length
            }
        val capture = ConversationDictationCallerAudio(StoppedCaptureDevice, buffer, writer)
        capture.finish {}
        try {
            repeat(2) {
                val stream = checkNotNull(capture.openProviderStream())
                val closed = CountDownLatch(1)
                stream.onFeedClosed { closed.countDown() }
                try {
                    assertTrue(stream.start())
                    assertTrue(closed.await(5, TimeUnit.SECONDS))
                    assertTrue(stream.acknowledge())
                } finally {
                    stream.cancel()
                    stream.closeProviderEnd()
                }
            }
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), bytes)
            assertFalse(capture.hasPending())
        } finally {
            capture.discard()
        }
    }

    /** Voice-command completion closes capture only after proving its acknowledged FIFO boundary. */
    @Test
    fun voiceCommandClosesCaptureAtAcknowledgedChunkAndDropsOnlyItsTail() {
        val buffer = ConversationDictationAudioChunkBuffer(2L, chunkBytes = 4, maxBufferedBytes = 12)
        buffer.append(byteArrayOf(1, 2, 3, 4, 5, 6), 6, hasSpeech = true)
        val commandChunk = checkNotNull(buffer.poll())
        assertTrue(buffer.acknowledge(commandChunk.chunkId))
        val capture = ConversationDictationCallerAudio(StoppedCaptureDevice, buffer)
        var boundaryAccepted: Boolean? = null

        assertTrue(capture.finishAtVoiceCommand(commandChunk.chunkId) { boundaryAccepted = it })

        assertEquals(true, boundaryAccepted)
        assertFalse(capture.hasPending())
        assertFalse(capture.start())
        assertNull(capture.openProviderStream())
    }

    /** Boundary rejection frees detached PCM immediately instead of retaining an orphaned audio queue. */
    @Test
    fun rejectedVoiceCommandBoundaryDiscardsDetachedAudio() {
        val buffer = ConversationDictationAudioChunkBuffer(3L, chunkBytes = 4, maxBufferedBytes = 8)
        buffer.append(byteArrayOf(1, 2, 3, 4), 4, hasSpeech = true)
        val capture = ConversationDictationCallerAudio(StoppedCaptureDevice, buffer)
        var boundaryAccepted: Boolean? = null

        assertFalse(capture.finishAtVoiceCommand(1L) { boundaryAccepted = it })

        assertEquals(false, boundaryAccepted)
        assertFalse(capture.hasPending())
    }

    /** Voice-command latency bounds never alter ordinary dictation's provider-safe defaults. */
    @Test
    fun voiceCommandChunkBoundsAreSessionScoped() {
        assertEquals(10..30, conversationDictationCallerAudioChunkSeconds(false))
        assertEquals(5..15, conversationDictationCallerAudioChunkSeconds(true))
    }

    /** Fails immediately if draining attempts to reopen the already stopped microphone. */
    private object StoppedCaptureDevice : ConversationDictationAudioCaptureDevice {
        override val initialized: Boolean = true
        override val recording: Boolean = false

        /** Rejects any attempt to reacquire the microphone while draining a sealed capture. */
        override fun start(): Unit = error("Draining must not restart microphone capture")

        /** Exposes no live samples because this fixture represents a recorder that is already stopped. */
        override fun read(target: ShortArray): Int = 0

        /** Requires no native cleanup for the already-stopped fixture. */
        override fun stop() = Unit

        /** Keeps repeated cleanup safe for the recorder-free drain fixture. */
        override fun release() = Unit
    }
}
