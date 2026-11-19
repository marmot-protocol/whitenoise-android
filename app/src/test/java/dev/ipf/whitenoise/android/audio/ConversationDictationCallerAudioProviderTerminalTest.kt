package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A provider terminal callback ends an unfinished feed at once instead of waiting for the pipe stall. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCallerAudioProviderTerminalTest {
    /** An idle provider end before any chunk is claimed closes the feed and leaves later audio queued. */
    @Test
    fun terminalBeforeClaimClosesTheFeedAtOnce() {
        val releaseRead = CountDownLatch(1)
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 4L, chunkBytes = 4, maxBufferedBytes = 8)
        val capture =
            ConversationDictationCallerAudio(
                object : ConversationDictationAudioCaptureDevice {
                    override val initialized = true
                    override val recording = true

                    override fun start() = Unit

                    override fun stop() = Unit

                    override fun release() = Unit

                    override fun read(
                        target: ShortArray,
                        waitForSamples: Boolean,
                    ): Int {
                        releaseRead.await(5, TimeUnit.SECONDS)
                        return 0
                    }
                },
                buffer,
                ConversationDictationAudioPipeWriter { _, _, _, length -> length },
            ) { 0L }
        val failures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val stream = checkNotNull(capture.openProviderStream(failures::add))
        try {
            assertTrue(stream.start())
            val closed = CountDownLatch(1)
            stream.endFeedAfterProviderTerminal(closed::countDown)
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty())
            assertNull(stream.chunkId())
            assertNull(stream.requestVersion())
            assertFalse(stream.fullyFed())

            assertTrue(buffer.append(PCM, PCM.size, hasSpeech = true))
            Thread.sleep(FEEDER_POLLS_MILLIS)
            assertArrayEquals(PCM, checkNotNull(buffer.poll()).pcm)
            assertTrue(failures.isEmpty())
        } finally {
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
            releaseRead.countDown()
        }
    }

    /** A terminal callback mid-write requeues the exact chunk with its identity and reports a pipe failure first. */
    @Test
    fun terminalMidWriteRequeuesTheExactChunkWithoutAStall() {
        val writes = AtomicInteger(0)
        val stalled = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val buffer = pcmBuffer()
        // A frozen clock means the old path could only close through the stall deadline, never here.
        val capture =
            sealedCapture(
                buffer,
                ConversationDictationAudioPipeWriter { _, _, _, _ ->
                    worker.set(Thread.currentThread())
                    if (writes.incrementAndGet() == 1) {
                        2
                    } else {
                        stalled.countDown()
                        0
                    }
                },
            )
        val failures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val stream = checkNotNull(capture.openProviderStream(failures::add))
        try {
            assertTrue(stream.start())
            assertTrue(stalled.await(2, TimeUnit.SECONDS))
            val failuresAtClose = AtomicReference<List<ConversationDictationCallerAudioFailure>>()
            val closed = CountDownLatch(1)
            stream.endFeedAfterProviderTerminal {
                failuresAtClose.set(failures.toList())
                closed.countDown()
            }
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            val feeder = checkNotNull(worker.get())
            feeder.join(2_000L)
            assertFalse(feeder.isAlive)
            val pipeFailed = listOf(ConversationDictationCallerAudioFailure.PipeFailed)
            assertEquals(pipeFailed, failuresAtClose.get())
            assertEquals(pipeFailed, failures.toList())
            assertFalse(stream.fullyFed())
            assertNull(stream.request())
            assertEquals(1L, stream.chunkId())
            assertEquals("1:4:0", stream.requestVersion())
            assertArrayEquals(PCM, checkNotNull(buffer.poll()).pcm)
        } finally {
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** A fully fed stream ignores the early-end path, so its accepted result still settles normally. */
    @Test
    fun fullyFedResultIsUnaffected() {
        val buffer = pcmBuffer()
        val capture = sealedCapture(buffer, ConversationDictationAudioPipeWriter { _, _, _, length -> length })
        val failures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val stream = checkNotNull(capture.openProviderStream(failures::add))
        try {
            val fed = CountDownLatch(1)
            stream.onFeedClosed(fed::countDown)
            assertTrue(stream.start())
            assertTrue(fed.await(2, TimeUnit.SECONDS))
            assertTrue(stream.fullyFed())

            val closed = CountDownLatch(1)
            stream.endFeedAfterProviderTerminal(closed::countDown)
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(stream.fullyFed())
            assertNotNull(stream.request())
            assertEquals(1L, stream.chunkId())
            assertNotNull(stream.acknowledge("hello"))
            assertFalse(buffer.hasPending)
            assertTrue(failures.isEmpty())
        } finally {
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** A terminal during the final write requeues the chunk and reports a pipe failure once that write returns. */
    @Test
    fun terminalDuringTheFinalWriteRequeuesTheChunk() {
        val inWrite = CountDownLatch(1)
        val terminalSent = CountDownLatch(1)
        val buffer = pcmBuffer()
        val capture =
            sealedCapture(
                buffer,
                ConversationDictationAudioPipeWriter { _, _, _, length ->
                    inWrite.countDown()
                    terminalSent.await(2, TimeUnit.SECONDS)
                    length
                },
            )
        val failures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val stream = checkNotNull(capture.openProviderStream(failures::add))
        try {
            assertTrue(stream.start())
            assertTrue(inWrite.await(2, TimeUnit.SECONDS))
            val failuresAtClose = AtomicReference<List<ConversationDictationCallerAudioFailure>>()
            val closed = CountDownLatch(1)
            stream.endFeedAfterProviderTerminal {
                failuresAtClose.set(failures.toList())
                closed.countDown()
            }
            terminalSent.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            val pipeFailed = listOf(ConversationDictationCallerAudioFailure.PipeFailed)
            assertEquals(pipeFailed, failuresAtClose.get())
            assertEquals(pipeFailed, failures.toList())
            assertFalse(stream.fullyFed())
            assertNull(stream.acknowledge("hello"))
            assertEquals(1L, stream.chunkId())
            assertEquals("1:4:0", stream.requestVersion())
            assertArrayEquals(PCM, checkNotNull(buffer.poll()).pcm)
        } finally {
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** A terminal callback racing the feeder's claim never closes the feed while the chunk is unaccounted for. */
    @Test
    fun terminalRacingAClaimLeavesTheChunkQueuedOrIdentified() {
        repeat(RACE_ATTEMPTS) {
            val buffer = pcmBuffer()
            val capture = sealedCapture(buffer, ConversationDictationAudioPipeWriter { _, _, _, _ -> 0 })
            val stream = checkNotNull(capture.openProviderStream {})
            try {
                assertTrue(stream.start())
                val accounted = AtomicReference<Boolean>()
                val closed = CountDownLatch(1)
                stream.endFeedAfterProviderTerminal {
                    accounted.set(buffer.hasPending || stream.chunkId() != null)
                    closed.countDown()
                }
                assertTrue(closed.await(2, TimeUnit.SECONDS))
                assertEquals(true, accounted.get())
                assertArrayEquals(PCM, checkNotNull(buffer.poll()).pcm)
            } finally {
                stream.cancel()
                stream.closeProviderEnd()
                capture.discard {}
            }
        }
    }

    private fun pcmBuffer() =
        ConversationDictationAudioChunkBuffer(sessionId = 4L, chunkBytes = 4, maxBufferedBytes = 8).also {
            assertTrue(it.append(PCM, PCM.size, hasSpeech = true))
        }

    private fun sealedCapture(
        buffer: ConversationDictationAudioChunkBuffer,
        writer: ConversationDictationAudioPipeWriter,
    ): ConversationDictationCallerAudio =
        ConversationDictationCallerAudio(
            object : ConversationDictationAudioCaptureDevice {
                override val initialized = true
                override val recording = false

                override fun start() = throw AssertionError("sealed feed must not reopen the microphone")

                override fun read(
                    target: ShortArray,
                    waitForSamples: Boolean,
                ): Int = throw AssertionError("capture sealed")

                override fun stop() = Unit

                override fun release() = Unit
            },
            buffer,
            writer,
        ) { 0L }.also { it.finish {} }

    private companion object {
        val PCM = byteArrayOf(1, 2, 3, 4)

        /** Several 10 ms feeder polls: long enough for a still-running feeder to claim queued audio. */
        const val FEEDER_POLLS_MILLIS = 100L

        const val RACE_ATTEMPTS = 200
    }
}
