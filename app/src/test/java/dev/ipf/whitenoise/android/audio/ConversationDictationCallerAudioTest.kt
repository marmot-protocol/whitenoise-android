package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCallerAudioTest {
    @Test
    fun cancelBeforePollReleasesTheStreamLease() {
        val capture = callerAudio(FakeCaptureDevice())
        val first = checkNotNull(capture.openProviderStream())

        first.cancel()

        assertNotNull(capture.openProviderStream())
        capture.discard {}
    }

    @Test
    fun pipeWriteFailureRequeuesAudioAndReleasesTheStreamLease() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 2L, chunkBytes = 4, maxBufferedBytes = 8)
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4), 4))
        val capture =
            callerAudio(buffer = buffer) { _, _, _, _ ->
                throw IOException("closed provider pipe")
            }
        val first = checkNotNull(capture.openProviderStream())
        val feedClosed = CountDownLatch(1)
        first.onFeedClosed(feedClosed::countDown)

        assertTrue(first.start())
        assertTrue(feedClosed.await(2, TimeUnit.SECONDS))

        assertTrue(buffer.hasPending)
        assertNotNull(capture.openProviderStream())
        capture.discard {}
    }

    @Test
    fun bufferOverflowReportsTypedFailureInsteadOfLeavingCaptureApparentlyActive() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 3L, chunkBytes = 4, maxBufferedBytes = 4)
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4), 4))
        val allowWrite = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val writer =
            ConversationDictationAudioPipeWriter { _, _, _, length ->
                allowWrite.await(2, TimeUnit.SECONDS)
                length
            }
        val capture =
            callerAudio(
                device = FakeCaptureDevice(listOf(shortArrayOf(5, 6))),
                buffer = buffer,
                writer = writer,
            )
        val stream = checkNotNull(capture.openProviderStream(failures::add))

        assertTrue(stream.start())
        await { failures.isNotEmpty() }

        assertTrue(failures.single() == ConversationDictationCallerAudioFailure.BufferFull)
        allowWrite.countDown()
        capture.discard {}
    }

    private fun callerAudio(
        device: ConversationDictationAudioCaptureDevice = FakeCaptureDevice(),
        buffer: ConversationDictationAudioChunkBuffer =
            ConversationDictationAudioChunkBuffer(sessionId = 1L, chunkBytes = 4, maxBufferedBytes = 8),
        writer: ConversationDictationAudioPipeWriter =
            ConversationDictationAudioPipeWriter { _, _, _, length -> length },
    ): ConversationDictationCallerAudio = ConversationDictationCallerAudio(device, buffer, writer)

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(predicate())
    }

    private class FakeCaptureDevice(
        reads: List<ShortArray> = emptyList(),
    ) : ConversationDictationAudioCaptureDevice {
        private val queuedReads = ArrayDeque(reads)

        @Volatile private var running = true

        override val initialized: Boolean = true

        override val recording: Boolean = true

        override fun start() = Unit

        override fun read(target: ShortArray): Int {
            val next = synchronized(queuedReads) { queuedReads.removeFirstOrNull() }
            if (next != null) {
                next.copyInto(target)
                return next.size
            }
            if (running) Thread.sleep(5)
            return 0
        }

        override fun stop() {
            running = false
        }

        override fun release() {
            running = false
        }
    }
}
