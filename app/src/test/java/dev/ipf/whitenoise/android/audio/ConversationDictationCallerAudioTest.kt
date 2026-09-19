package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4), 4, hasSpeech = true))
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

    /** Finishing retains the in-progress read plus five native recorder tail reads. */
    @Test
    fun finishDrainsInProgressReadAndFiveTailReadsThenCloses() {
        val device = FinishingReadCaptureDevice()
        val writes = CopyOnWriteArrayList<Int>()
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 7L, chunkBytes = 24, maxBufferedBytes = 24)
        val capture =
            callerAudio(
                device = device,
                buffer = buffer,
                writer = ConversationDictationAudioPipeWriter { _, _, _, length -> length.also(writes::add) },
            )
        val stream = checkNotNull(capture.openProviderStream())
        val captureClosed = CountDownLatch(1)
        val duplicateFinishClosed = CountDownLatch(1)
        val feedClosed = CountDownLatch(1)
        stream.onFeedClosed(feedClosed::countDown)
        try {
            assertTrue(stream.start())
            assertTrue(device.readStarted.await(2, TimeUnit.SECONDS))

            stream.finishCapture(captureClosed::countDown)
            stream.finishCapture(duplicateFinishClosed::countDown)
            device.completeRead.countDown()

            assertTrue(captureClosed.await(2, TimeUnit.SECONDS))
            assertTrue(duplicateFinishClosed.await(2, TimeUnit.SECONDS))
            assertTrue(feedClosed.await(2, TimeUnit.SECONDS))
            assertEquals(6, device.readCount.get())
            assertEquals(24, writes.sum())
            assertEquals(true, stream.containsSpeech())
            assertTrue(buffer.hasPending)
        } finally {
            device.completeRead.countDown()
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** Cancellation during an armed tail drain cannot repopulate the discarded PCM buffer. */
    @Test
    fun discardDuringTailDrainWinsWithoutRetainingLaterReads() {
        val device = FinishingReadCaptureDevice()
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 8L, chunkBytes = 24, maxBufferedBytes = 24)
        val capture = callerAudio(device = device, buffer = buffer)
        val stream = checkNotNull(capture.openProviderStream())
        val finishClosed = CountDownLatch(1)
        val discardClosed = CountDownLatch(1)
        try {
            assertTrue(stream.start())
            assertTrue(device.readStarted.await(2, TimeUnit.SECONDS))

            stream.finishCapture(finishClosed::countDown)
            capture.discard(discardClosed::countDown)
            device.completeRead.countDown()

            assertTrue(finishClosed.await(2, TimeUnit.SECONDS))
            assertTrue(discardClosed.await(2, TimeUnit.SECONDS))
            assertFalse(buffer.hasPending)
        } finally {
            device.completeRead.countDown()
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** Slow successful recorder reads cannot extend the post-action drain beyond its deadline. */
    @Test
    fun finishBoundsTailDrainByElapsedTimeAsWellAsReadCount() {
        val clock = FakeElapsedRealtime()
        val device = DeadlineReadCaptureDevice(clock)
        val capture = callerAudio(device = device, elapsedRealtime = clock::now)
        val stream = checkNotNull(capture.openProviderStream())
        val captureClosed = CountDownLatch(1)
        try {
            assertTrue(stream.start())
            assertTrue(device.readStarted.await(2, TimeUnit.SECONDS))

            stream.finishCapture(captureClosed::countDown)
            device.completeRead.countDown()

            assertTrue(captureClosed.await(2, TimeUnit.SECONDS))
            assertEquals(2, device.readCount.get())
        } finally {
            device.completeRead.countDown()
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** An attached provider must receive the typed overflow error when continuous capture exhausts its bound. */
    @Test
    fun bufferOverflowReportsTypedFailureInsteadOfLeavingCaptureApparentlyActive() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 3L, chunkBytes = 4, maxBufferedBytes = 4)
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4), 4, hasSpeech = true))
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
        assertTrue(buffer.append(byteArrayOf(1, 2, 3, 4), 4, hasSpeech = true))
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

    /** Nonzero PCM below the sentence-boundary peak remains retryable after a blank provider final. */
    @Test
    fun subThresholdNonzeroPcmRemainsSpeechBearing() {
        val samples = shortArrayOf(1, -1, 2, -2)
        assertTrue(conversationDictationPeak(samples, samples.size) < 0.02f)
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 5L, chunkBytes = 8, maxBufferedBytes = 16)
        val capture = callerAudio(device = FakeCaptureDevice(listOf(samples)), buffer = buffer)
        val stream = checkNotNull(capture.openProviderStream())
        val feedClosed = CountDownLatch(1)
        stream.onFeedClosed(feedClosed::countDown)
        try {
            assertTrue(stream.start())
            assertTrue(feedClosed.await(2, TimeUnit.SECONDS))

            assertEquals(true, stream.containsSpeech())
            assertTrue(stream.retry())
            assertTrue(buffer.hasPending)
        } finally {
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** A short utterance remains one provider-safe tail chunk until explicit completion. */
    @Test
    fun shortUtteranceWaitsForFinishBelowProviderSafeMinimum() {
        val clock = FakeElapsedRealtime()
        val device = ShortUtteranceGapCaptureDevice(clock)
        val writes = CopyOnWriteArrayList<Int>()
        val buffer =
            ConversationDictationAudioChunkBuffer(
                sessionId = 6L,
                chunkBytes = 960_000,
                maxBufferedBytes = 2_880_000,
            )
        val capture =
            callerAudio(
                device = device,
                buffer = buffer,
                writer =
                    ConversationDictationAudioPipeWriter { _, _, _, length ->
                        writes += length
                        length
                    },
                elapsedRealtime = clock::now,
            )
        val stream = checkNotNull(capture.openProviderStream())
        val captureClosed = CountDownLatch(1)
        val feedClosed = CountDownLatch(1)
        stream.onFeedClosed(feedClosed::countDown)
        try {
            assertTrue(stream.start())
            assertTrue(device.waitingForEnd.await(2, TimeUnit.SECONDS))
            assertTrue(writes.isEmpty())

            stream.finishCapture(captureClosed::countDown)
            device.allowEnd.countDown()
            assertTrue(captureClosed.await(2, TimeUnit.SECONDS))
            assertTrue(feedClosed.await(2, TimeUnit.SECONDS))

            assertEquals(listOf(80_000), writes)
            assertEquals(true, stream.containsSpeech())
        } finally {
            device.allowEnd.countDown()
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** Continuous speech stays in one chunk until 500 ms of actual quiet, with every read in progress. */
    @Test
    fun continuousSpeechSealsOnlyAfterQuietGapAndReportsEveryRead() {
        ShadowLog.clear()
        val clock = FakeElapsedRealtime()
        val device = QuietGapCaptureDevice(clock)
        val writes = CopyOnWriteArrayList<Int>()
        val buffer =
            ConversationDictationAudioChunkBuffer(
                sessionId = 6L,
                chunkBytes = 960_000,
                maxBufferedBytes = 2_880_000,
            )
        val capture =
            callerAudio(
                device = device,
                buffer = buffer,
                writer =
                    ConversationDictationAudioPipeWriter { _, _, _, length ->
                        writes += length
                        length
                    },
                elapsedRealtime = clock::now,
            )
        val stream = checkNotNull(capture.openProviderStream())
        try {
            assertTrue(stream.start())
            assertTrue(device.waitingForQuiet.await(2, TimeUnit.SECONDS))

            assertTrue(writes.isEmpty())
            val speechProgress =
                ShadowLog
                    .getLogsForTag("WNDictation")
                    .map { it.msg }
                    .filter { it.startsWith("event=caller_audio_progress") }
            assertEquals(10, speechProgress.size)
            assertTrue(speechProgress.last().contains("bytes=320000"))

            device.allowQuiet.countDown()
            assertTrue(device.waitingForEnd.await(2, TimeUnit.SECONDS))
            await { writes.isNotEmpty() }

            assertEquals(listOf(336_000), writes)
            assertEquals(true, stream.containsSpeech())
        } finally {
            device.allowQuiet.countDown()
            device.allowEnd.countDown()
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** Builds a real capture with small bounded PCM storage and an injectable device and writer. */
    private fun callerAudio(
        device: ConversationDictationAudioCaptureDevice = FakeCaptureDevice(),
        buffer: ConversationDictationAudioChunkBuffer =
            ConversationDictationAudioChunkBuffer(sessionId = 1L, chunkBytes = 4, maxBufferedBytes = 8),
        elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime,
        writer: ConversationDictationAudioPipeWriter =
            ConversationDictationAudioPipeWriter { _, _, _, length -> length },
    ): ConversationDictationCallerAudio =
        ConversationDictationCallerAudio(
            device = device,
            buffer = buffer,
            pipeWriter = writer,
            elapsedRealtime = elapsedRealtime,
        )

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

    private class FakeElapsedRealtime {
        private val millis = AtomicLong(0L)

        fun now(): Long = millis.get()

        fun advance(millis: Long) {
            this.millis.addAndGet(millis)
        }
    }

    /** Holds one read across the terminal action, then exposes more native-buffered tail reads. */
    private class FinishingReadCaptureDevice : ConversationDictationAudioCaptureDevice {
        val readStarted = CountDownLatch(1)
        val completeRead = CountDownLatch(1)
        val readCount = AtomicInteger(0)

        override val initialized: Boolean = true

        override val recording: Boolean = true

        override fun start() = Unit

        override fun read(target: ShortArray): Int {
            if (readCount.getAndIncrement() == 0) {
                readStarted.countDown()
                check(completeRead.await(2, TimeUnit.SECONDS))
            }
            target[0] = 1_000
            target[1] = 1_000
            return 2
        }

        override fun stop() = Unit

        override fun release() {
            completeRead.countDown()
        }
    }

    /** Advances the injected clock by 400 ms for each successful native read. */
    private class DeadlineReadCaptureDevice(
        private val clock: FakeElapsedRealtime,
    ) : ConversationDictationAudioCaptureDevice {
        val readStarted = CountDownLatch(1)
        val completeRead = CountDownLatch(1)
        val readCount = AtomicInteger(0)

        override val initialized: Boolean = true

        override val recording: Boolean = true

        override fun start() = Unit

        override fun read(target: ShortArray): Int {
            if (readCount.getAndIncrement() == 0) {
                readStarted.countDown()
                check(completeRead.await(2, TimeUnit.SECONDS))
            }
            clock.advance(400L)
            target[0] = 1_000
            target[1] = 1_000
            return 2
        }

        override fun stop() = Unit

        override fun release() {
            completeRead.countDown()
        }
    }

    /** Holds the recorder after continuous speech and again after the exact five-read quiet gap. */
    private class QuietGapCaptureDevice(
        private val clock: FakeElapsedRealtime,
    ) : ConversationDictationAudioCaptureDevice {
        val waitingForQuiet = CountDownLatch(1)
        val allowQuiet = CountDownLatch(1)
        val waitingForEnd = CountDownLatch(1)
        val allowEnd = CountDownLatch(1)
        private var speechReads = 0
        private var quietReads = 0

        override val initialized: Boolean = true

        override val recording: Boolean = true

        override fun start() = Unit

        override fun read(target: ShortArray): Int {
            when {
                speechReads < 100 -> {
                    target.fill(1_000)
                    speechReads += 1
                }
                quietReads < 5 -> {
                    if (quietReads == 0) {
                        waitingForQuiet.countDown()
                        check(allowQuiet.await(2, TimeUnit.SECONDS))
                    }
                    target.fill(0)
                    quietReads += 1
                }
                else -> {
                    waitingForEnd.countDown()
                    check(allowEnd.await(2, TimeUnit.SECONDS))
                    return 0
                }
            }
            clock.advance(100L)
            return target.size
        }

        override fun stop() = Unit

        override fun release() {
            allowQuiet.countDown()
            allowEnd.countDown()
        }
    }

    /** Produces two seconds of speech and an exact 500 ms quiet boundary, then holds capture open. */
    private class ShortUtteranceGapCaptureDevice(
        private val clock: FakeElapsedRealtime,
    ) : ConversationDictationAudioCaptureDevice {
        val waitingForEnd = CountDownLatch(1)
        val allowEnd = CountDownLatch(1)
        private var speechReads = 0
        private var quietReads = 0

        override val initialized: Boolean = true

        override val recording: Boolean = true

        override fun start() = Unit

        override fun read(target: ShortArray): Int {
            when {
                speechReads < 20 -> {
                    target.fill(1_000)
                    speechReads += 1
                }
                quietReads < 5 -> {
                    target.fill(0)
                    quietReads += 1
                }
                else -> {
                    waitingForEnd.countDown()
                    check(allowEnd.await(2, TimeUnit.SECONDS))
                    return 0
                }
            }
            clock.advance(100L)
            return target.size
        }

        override fun stop() = Unit

        override fun release() {
            allowEnd.countDown()
        }
    }
}
