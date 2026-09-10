package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    /** Cancelling a stream before it claims PCM must still free the lease for a replacement. */
    @Test
    fun cancelBeforePollReleasesTheStreamLease() {
        val capture = callerAudio(FakeCaptureDevice())
        val first = checkNotNull(capture.openProviderStream())

        first.cancel()

        assertNotNull(capture.openProviderStream())
        capture.discard {}
    }

    /** A disconnected pipe must retain its unacknowledged chunk and release the generation lease. */
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

    /** An attached provider must receive the typed overflow error when continuous capture exhausts its bound. */
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

    /** An overflow between generations must reach the next stream once, never the settled stream. */
    @Test
    fun overflowBetweenStreamsIsReportedOnceToTheNextGeneration() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 4L, chunkBytes = 4, maxBufferedBytes = 4)
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4), 4))
        val allowRead = CountDownLatch(1)
        val captureClosed = CountDownLatch(1)
        val device =
            object : ConversationDictationAudioCaptureDevice by FakeCaptureDevice() {
                /** Blocks the overflow read until the previous provider stream has released its lease. */
                override fun read(target: ShortArray): Int {
                    check(allowRead.await(2, TimeUnit.SECONDS))
                    target[0] = 5
                    return 1
                }

                /** Signals that the overflow read has finished and the recorder is closed. */
                override fun release() = captureClosed.countDown()
            }
        val failures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val settledFailures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val capture = callerAudio(device, buffer)
        val first = checkNotNull(capture.openProviderStream(settledFailures::add))
        try {
            assertTrue(capture.start())
            first.cancel()
            first.closeProviderEnd()
            allowRead.countDown()
            assertTrue(captureClosed.await(2, TimeUnit.SECONDS))
            assertTrue(settledFailures.isEmpty())

            val next = checkNotNull(capture.openProviderStream(failures::add))
            assertEquals(listOf(ConversationDictationCallerAudioFailure.BufferFull), failures)
            next.cancel()
            next.closeProviderEnd()
            val last = checkNotNull(capture.openProviderStream(failures::add))
            assertEquals(1, failures.size)
            last.cancel()
            last.closeProviderEnd()
        } finally {
            allowRead.countDown()
            capture.discard {}
            first.closeProviderEnd()
        }
    }

    /** A stream whose buffer is already fully drained closes instead of polling forever. */
    @Test
    fun drainedBufferClosesFeedWithoutWaitingForAnotherChunk() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 5L, chunkBytes = 4, maxBufferedBytes = 8)
        buffer.finish()
        val capture = callerAudio(buffer = buffer)
        val stream = checkNotNull(capture.openProviderStream())
        val feedClosed = CountDownLatch(1)
        stream.onFeedClosed(feedClosed::countDown)

        assertTrue(stream.start())
        assertTrue(feedClosed.await(2, TimeUnit.SECONDS))

        assertFalse(stream.acknowledge())
        assertFalse(capture.hasPending())
        capture.discard {}
    }

    /** A stream whose tail chunk drains before it asks closes after that already polled chunk. */
    @Test
    fun drainCompletedBeforeFeedSettlesClosesTheStreamLease() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 6L, chunkBytes = 4, maxBufferedBytes = 8)
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4), 4))
        buffer.finish()
        val capture = callerAudio(buffer = buffer)
        val stream = checkNotNull(capture.openProviderStream())
        val feedClosed = CountDownLatch(1)
        stream.onFeedClosed(feedClosed::countDown)

        assertTrue(stream.start())
        assertTrue(feedClosed.await(2, TimeUnit.SECONDS))

        assertTrue(stream.acknowledge())
        assertFalse(capture.hasPending())
        capture.discard {}
    }

    /** Builds a real capture with small bounded PCM storage and an injectable device and writer. */
    private fun callerAudio(
        device: ConversationDictationAudioCaptureDevice = FakeCaptureDevice(),
        buffer: ConversationDictationAudioChunkBuffer =
            ConversationDictationAudioChunkBuffer(sessionId = 1L, chunkBytes = 4, maxBufferedBytes = 8),
        writer: ConversationDictationAudioPipeWriter =
            ConversationDictationAudioPipeWriter { _, _, _, length -> length },
    ): ConversationDictationCallerAudio = ConversationDictationCallerAudio(device, buffer, writer)

    /** Bounds asynchronous capture assertions so a missing callback fails instead of hanging the suite. */
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

        /** Uses an already-ready fake device to exercise capture without acquiring a physical microphone. */
        override fun start() = Unit

        /** Returns queued test samples, or a terminal read after the finite test input is consumed. */
        override fun read(target: ShortArray): Int {
            val next = synchronized(queuedReads) { queuedReads.removeFirstOrNull() }
            if (next != null) {
                next.copyInto(target)
                return next.size
            }
            if (running) Thread.sleep(5)
            return 0
        }

        /** Stops the fake device’s wait path after capture completion or cancellation. */
        override fun stop() {
            running = false
        }

        /** Marks the test device closed so capture completion is observable without native recorder resources. */
        override fun release() {
            running = false
        }
    }
}
