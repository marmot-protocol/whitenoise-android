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
        buffer.append(byteArrayOf(1, 2, 3, 4, 5, 6), 6)
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
        buffer.append(byteArrayOf(1, 2, 3, 4, 5, 6), 6)
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

    /** Fails immediately if draining attempts to reopen the already stopped microphone. */
    private object StoppedCaptureDevice : ConversationDictationAudioCaptureDevice {
        override val initialized: Boolean = true
        override val recording: Boolean = false

        override fun start(): Unit = error("Draining must not restart microphone capture")

        override fun read(target: ShortArray): Int = 0

        override fun stop() = Unit

        override fun release() = Unit
    }
}
