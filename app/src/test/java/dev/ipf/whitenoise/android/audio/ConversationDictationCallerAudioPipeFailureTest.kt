package dev.ipf.whitenoise.android.audio

import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCallerAudioPipeFailureTest {
    /** Every failed write preserves the entire unacknowledged chunk, including a written prefix. */
    @Test
    fun pipeExceptionsRequeueExactBytesAndNotifyBeforeFeedClosure() {
        listOf(
            IOException("pipe closed"),
            ErrnoException("write", OsConstants.EPIPE),
            IllegalStateException("descriptor unavailable"),
        ).forEach { failure ->
            val writes = AtomicInteger(0)
            verifyPipeFailure { _, _, _, _ ->
                if (writes.incrementAndGet() == 1) 2 else throw failure
            }
        }
    }

    /** A zero-return write cannot busy-spin; it shares the existing bounded EAGAIN deadline. */
    @Test
    fun zeroAndEagainStallsNotifyOnceWithoutAcknowledgingAudio() {
        listOf(false, true).forEach { eagain ->
            verifyPipeFailure { _, _, _, _ ->
                if (eagain) throw ErrnoException("write", OsConstants.EAGAIN)
                0
            }
        }
    }

    private fun verifyPipeFailure(writer: ConversationDictationAudioPipeWriter) {
        val buffer = pcmBuffer()
        val clock = AtomicLong(0L)
        val capture = sealedCapture(buffer, writer) { clock.getAndAdd(10_000L) }
        val events = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val stream =
            checkNotNull(
                capture.openProviderStream {
                    failures.add(it)
                    events.add("failure")
                },
            )
        val closed = CountDownLatch(1)
        stream.onFeedClosed {
            events.add("closed")
            closed.countDown()
        }
        try {
            assertTrue(stream.start())
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(ConversationDictationCallerAudioFailure.PipeFailed), failures)
            assertEquals(listOf("failure", "closed"), events)
            assertFalse(stream.fullyFed())
            assertTrue(buffer.hasPending)
            val retained = checkNotNull(buffer.poll())
            assertArrayEquals(PCM, retained.pcm)
            assertTrue(buffer.retry(retained.chunkId))
            val replacement = checkNotNull(capture.openProviderStream())
            replacement.cancel()
            replacement.closeProviderEnd()
        } finally {
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** Real write progress resets the stall budget rather than timing the whole chunk. */
    @Test
    fun successfulPartialWritesResetTheStallDeadline() {
        val writes = AtomicInteger(0)
        val clock = AtomicLong(0L)
        val buffer = pcmBuffer()
        val capture =
            sealedCapture(
                buffer,
                ConversationDictationAudioPipeWriter { _, _, _, _ ->
                    if (writes.incrementAndGet() % 2 == 1) 0 else 2
                },
            ) { clock.getAndAdd(10_000L) }
        val failures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val stream = checkNotNull(capture.openProviderStream(failures::add))
        val closed = CountDownLatch(1)
        stream.onFeedClosed(closed::countDown)
        try {
            assertTrue(stream.start())
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(stream.fullyFed())
            assertTrue(failures.isEmpty())
            assertTrue(stream.acknowledge())
            assertFalse(buffer.hasPending)
        } finally {
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** Cancellation racing a thrown write keeps recovery quiet and PCM byte-identical. */
    @Test
    fun cancellationDuringAWriteDoesNotReportProviderFailure() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val buffer = pcmBuffer()
        val capture =
            sealedCapture(
                buffer,
                ConversationDictationAudioPipeWriter { _, _, _, _ ->
                    worker.set(Thread.currentThread())
                    entered.countDown()
                    check(cancelled.await(5, TimeUnit.SECONDS))
                    throw IOException("cancelled pipe")
                },
            ) { 0L }
        val failures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val stream = checkNotNull(capture.openProviderStream(failures::add))
        try {
            assertTrue(stream.start())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stream.cancel()
            cancelled.countDown()
            val feeder = checkNotNull(worker.get())
            feeder.join(2_000L)
            assertFalse(feeder.isAlive)
            assertTrue(failures.isEmpty())
            assertFalse(stream.fullyFed())
            assertArrayEquals(PCM, checkNotNull(buffer.poll()).pcm)
        } finally {
            cancelled.countDown()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** A pipe failure cannot consume or mask the logical capture's later terminal receipt. */
    @Test
    fun captureAndPipeFailuresRemainIndependentInEitherOrder() {
        listOf(false, true).forEach(::verifyFailureOrder)
    }

    private fun verifyFailureOrder(captureFirst: Boolean) {
        with(FailureOrderFixture()) {
            try {
                assertTrue(old.start())
                assertTrue(readEntered.await(2, TimeUnit.SECONDS))
                assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
                if (captureFirst) {
                    failRead.countDown()
                    assertTrue(captureClosed.await(2, TimeUnit.SECONDS))
                }
                failWrite.countDown()
                assertTrue(feedClosed.await(2, TimeUnit.SECONDS))
                failRead.countDown()
                assertTrue(captureClosed.await(2, TimeUnit.SECONDS))
                val expected =
                    if (captureFirst) {
                        listOf(
                            ConversationDictationCallerAudioFailure.CaptureFailed,
                            ConversationDictationCallerAudioFailure.PipeFailed,
                        )
                    } else {
                        listOf(ConversationDictationCallerAudioFailure.PipeFailed)
                    }
                assertEquals(expected, oldFailures)
                assertTrue(capture.acknowledgeFailure(ConversationDictationCallerAudioFailure.PipeFailed))
                val nextFailures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
                val next = checkNotNull(capture.openProviderStream(nextFailures::add))
                assertEquals(listOf(ConversationDictationCallerAudioFailure.CaptureFailed), nextFailures)
                assertTrue(capture.acknowledgeFailure(ConversationDictationCallerAudioFailure.CaptureFailed))
                assertFalse(next.fullyFed())
                next.cancel()
                next.closeProviderEnd()
                assertArrayEquals(PCM, checkNotNull(buffer.poll()).pcm)
            } finally {
                failRead.countDown()
                failWrite.countDown()
                old.closeProviderEnd()
                capture.discard {}
            }
        }
    }

    private inner class FailureOrderFixture {
        val failRead = CountDownLatch(1)
        val failWrite = CountDownLatch(1)
        val readEntered = CountDownLatch(1)
        val writeEntered = CountDownLatch(1)
        val captureClosed = CountDownLatch(1)
        val feedClosed = CountDownLatch(1)
        val buffer = pcmBuffer()
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
                        readEntered.countDown()
                        check(failRead.await(5, TimeUnit.SECONDS))
                        return -6
                    }
                },
                buffer,
                ConversationDictationAudioPipeWriter { _, _, _, _ ->
                    writeEntered.countDown()
                    check(failWrite.await(5, TimeUnit.SECONDS))
                    throw IOException("pipe disconnected")
                },
            )
        val oldFailures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val old = checkNotNull(capture.openProviderStream(oldFailures::add))

        init {
            capture.onCaptureClosed(captureClosed::countDown)
            old.onFeedClosed(feedClosed::countDown)
        }
    }

    private fun pcmBuffer() =
        ConversationDictationAudioChunkBuffer(sessionId = 4L, chunkBytes = 4, maxBufferedBytes = 8).also {
            assertTrue(it.append(PCM, PCM.size, hasSpeech = true))
        }

    private fun sealedCapture(
        buffer: ConversationDictationAudioChunkBuffer,
        writer: ConversationDictationAudioPipeWriter,
        elapsedRealtime: () -> Long,
    ): ConversationDictationCallerAudio =
        ConversationDictationCallerAudio(
            object : ConversationDictationAudioCaptureDevice {
                override val initialized = true
                override val recording = false

                override fun start() = throw AssertionError("sealed Retry must not reopen the microphone")

                override fun read(
                    target: ShortArray,
                    waitForSamples: Boolean,
                ): Int = throw AssertionError("capture sealed")

                override fun stop() = Unit

                override fun release() = Unit
            },
            buffer,
            writer,
            elapsedRealtime,
        ).also { it.finish {} }

    private companion object {
        val PCM = byteArrayOf(1, 2, 3, 4)
    }
}
