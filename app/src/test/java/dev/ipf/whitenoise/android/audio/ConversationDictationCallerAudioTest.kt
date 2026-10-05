package dev.ipf.whitenoise.android.audio

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCallerAudioTest {
    /** Production discard/finish/forced closure adapters queue recorder-thread callbacks to main. */
    @Test
    fun nativeCaptureClosureIsDeliveredOnlyOnMain() {
        val nativeReturned = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val onClosed =
            mainThreadDictationCaptureClosure {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                delivered.set(true)
            }
        Thread {
            onClosed()
            nativeReturned.countDown()
        }.start()
        assertTrue(nativeReturned.await(2, TimeUnit.SECONDS))
        assertFalse(delivered.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(delivered.get())
    }

    /** The production owner keeps a discarded recorder addressable until its real release returns. */
    @Test
    fun discardedRecorderRemainsOwnedWhileNativeReleaseIsBlocked() {
        val readStarted = CountDownLatch(1)
        val returnRead = CountDownLatch(1)
        val releaseStarted = CountDownLatch(1)
        val returnRelease = CountDownLatch(1)
        val releases = AtomicInteger(0)
        val capture =
            callerAudio(
                object : ConversationDictationAudioCaptureDevice by FakeCaptureDevice() {
                    override fun read(
                        target: ShortArray,
                        waitForSamples: Boolean,
                    ): Int {
                        readStarted.countDown()
                        check(returnRead.await(5, TimeUnit.SECONDS))
                        return 0
                    }

                    override fun stop() = Unit

                    override fun release() {
                        releases.incrementAndGet()
                        releaseStarted.countDown()
                        check(returnRelease.await(5, TimeUnit.SECONDS))
                    }
                },
            )
        val owner = ConversationDictationCaptureOwner { capture }
        val discarded = AtomicBoolean(false)
        val forced = AtomicBoolean(false)
        try {
            assertTrue(owner.acquire() === capture)
            assertTrue(capture.start())
            assertTrue(readStarted.await(2, TimeUnit.SECONDS))
            assertTrue(owner.discard { discarded.set(true) })
            assertTrue(releaseStarted.await(2, TimeUnit.SECONDS))
            assertTrue(owner.forceClose { forced.set(true) })
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(discarded.get())
            assertFalse(forced.get())
            assertFalse(owner.hasPending())
            // A replacement whose provider records itself must use its own closure callback.
            owner.beginSession()
            val replacementClosed = AtomicBoolean(false)
            assertFalse(owner.finish { replacementClosed.set(true) })
            assertFalse(owner.forceClose { replacementClosed.set(true) })
            assertFalse(owner.discard { replacementClosed.set(true) })
            returnRelease.countDown()
            await {
                shadowOf(Looper.getMainLooper()).idle()
                discarded.get() && forced.get()
            }
            assertEquals(1, releases.get())
            assertFalse(replacementClosed.get())
            assertFalse(owner.forceClose {})
        } finally {
            returnRelease.countDown()
            returnRead.countDown()
        }
    }

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

        assertFalse(first.fullyFed())
        assertTrue(buffer.hasPending)
        assertNotNull(capture.openProviderStream())
        capture.discard {}
    }

    /** Forced closure interrupts a blocked native read, preserves completed PCM and releases once. */
    @Test
    fun forceFinishClosesBlockedRecorderWithoutDiscardingCompletedAudio() {
        val blockedRead = CountDownLatch(1)
        val stopRead = CountDownLatch(1)
        val released = AtomicInteger(0)
        val reads = AtomicInteger(0)
        val device =
            object : ConversationDictationAudioCaptureDevice by FakeCaptureDevice() {
                override fun read(
                    target: ShortArray,
                    waitForSamples: Boolean,
                ): Int {
                    if (reads.incrementAndGet() == 1) {
                        target[0] = 1
                        target[1] = 2
                        return 2
                    }
                    blockedRead.countDown()
                    check(stopRead.await(2, TimeUnit.SECONDS))
                    target[0] = 3
                    target[1] = 4
                    return 2
                }

                override fun stop() = stopRead.countDown()

                override fun release() {
                    released.incrementAndGet()
                }
            }
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 1L, chunkBytes = 4, maxBufferedBytes = 8)
        val capture = callerAudio(device = device, buffer = buffer)
        val closed = CountDownLatch(1)
        try {
            assertTrue(capture.start())
            assertTrue(blockedRead.await(2, TimeUnit.SECONDS))
            capture.forceFinish(closed::countDown)
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, released.get())
            assertTrue(buffer.hasPending)
            assertEquals(8, buffer.bufferedBytes)
            capture.forceFinish {}
            await { released.get() == 1 }
        } finally {
            stopRead.countDown()
            capture.discard {}
        }
    }

    /** A released recorder whose driver withholds its read cannot retain microphone ownership forever. */
    @Test
    fun forcedClosureSealsReleasedRecorderWhenReadDoesNotReturn() {
        val blockedRead = CountDownLatch(1)
        val returnRead = CountDownLatch(1)
        val captureFinally = CountDownLatch(1)
        val reads = AtomicInteger(0)
        val releases = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val device =
            object : ConversationDictationAudioCaptureDevice by FakeCaptureDevice() {
                override fun read(
                    target: ShortArray,
                    waitForSamples: Boolean,
                ): Int {
                    if (reads.incrementAndGet() > 1) {
                        blockedRead.countDown()
                        check(returnRead.await(5, TimeUnit.SECONDS))
                    }
                    target[0] = 1
                    target[1] = 2
                    return 2
                }

                override fun stop() {
                    if (stops.incrementAndGet() > 1) captureFinally.countDown()
                }

                override fun release() {
                    releases.incrementAndGet()
                }
            }
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 1L, chunkBytes = 4, maxBufferedBytes = 8)
        val capture = callerAudio(device = device, buffer = buffer)
        val closed = CountDownLatch(1)
        try {
            assertTrue(capture.start())
            assertTrue(blockedRead.await(2, TimeUnit.SECONDS))
            capture.forceFinish(closed::countDown)
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, releases.get())
            assertEquals(4, buffer.bufferedBytes)
            assertTrue(buffer.hasPending)
            returnRead.countDown()
            assertTrue(captureFinally.await(2, TimeUnit.SECONDS))
            assertEquals(4, buffer.bufferedBytes)
            assertEquals(1, releases.get())
        } finally {
            returnRead.countDown()
            capture.discard {}
        }
    }

    /** Finishing preserves the outstanding read and drains available samples without waiting for fresh audio. */
    @Test
    fun finishDrainsAvailableTailWithoutAdditionalBlockingReads() {
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
            assertFalse(stream.isFinalChunk())

            stream.finishCapture(captureClosed::countDown)
            stream.finishCapture(duplicateFinishClosed::countDown)
            device.completeRead.countDown()

            assertTrue(captureClosed.await(2, TimeUnit.SECONDS))
            assertTrue(duplicateFinishClosed.await(2, TimeUnit.SECONDS))
            assertTrue(feedClosed.await(2, TimeUnit.SECONDS))
            assertEquals(6, device.readCount.get())
            assertEquals(listOf(true, false, false, false, false, false), device.readModes)
            assertEquals(24, writes.sum())
            assertTrue(stream.fullyFed())
            assertTrue(stream.isFinalChunk())
            assertEquals(true, stream.containsSpeech())
            assertTrue(buffer.hasPending)
            assertTrue(stream.retry())
            assertFalse(stream.isFinalChunk())
            assertEquals(24, buffer.bufferedBytes)
        } finally {
            device.completeRead.countDown()
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** An empty native buffer closes immediately after the outstanding read, even with a stalled provider. */
    @Test
    fun finishWithEmptyNativeBufferClosesBeforeProviderFeedFinishes() {
        val device = FinishingReadCaptureDevice(availableReads = 0)
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 8L, chunkBytes = 24, maxBufferedBytes = 24)
        val allowFeed = CountDownLatch(1)
        val feedStarted = CountDownLatch(1)
        val feedClosed = CountDownLatch(1)
        val captureClosed = CountDownLatch(1)
        val capture =
            callerAudio(
                device = device,
                buffer = buffer,
                writer =
                    ConversationDictationAudioPipeWriter { _, _, _, length ->
                        feedStarted.countDown()
                        check(allowFeed.await(2, TimeUnit.SECONDS))
                        length
                    },
            )
        val stream = checkNotNull(capture.openProviderStream())
        stream.onFeedClosed(feedClosed::countDown)
        try {
            assertTrue(stream.start())
            assertTrue(device.readStarted.await(2, TimeUnit.SECONDS))
            stream.finishCapture(captureClosed::countDown)
            assertEquals(1L, captureClosed.count)
            device.completeRead.countDown()

            assertTrue(captureClosed.await(2, TimeUnit.SECONDS))
            assertTrue(feedStarted.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(true, false), device.readModes)
            assertEquals(4, buffer.bufferedBytes)
            assertEquals(1L, feedClosed.count)
            allowFeed.countDown()
            assertTrue(feedClosed.await(2, TimeUnit.SECONDS))
            assertTrue(stream.fullyFed())
            assertTrue(buffer.hasPending)
        } finally {
            device.completeRead.countDown()
            allowFeed.countDown()
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
                override fun read(
                    target: ShortArray,
                    waitForSamples: Boolean,
                ): Int {
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

    /** Finishing keeps the six recorder tail reads together instead of creating a tiny final request. */
    @Test
    fun finishDoesNotSplitAtASentenceBoundaryDuringRecorderDrain() {
        val clock = FakeElapsedRealtime()
        val device = QuietGapCaptureDevice(clock, finalRead = true)
        val writes = CopyOnWriteArrayList<Int>()
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 9L)
        val capture =
            callerAudio(
                device = device,
                buffer = buffer,
                writer = ConversationDictationAudioPipeWriter { _, _, _, length -> length.also(writes::add) },
                elapsedRealtime = clock::now,
            )
        val stream = checkNotNull(capture.openProviderStream())
        val closed = CountDownLatch(1)
        try {
            assertTrue(stream.start())
            assertTrue(device.waitingForQuiet.await(2, TimeUnit.SECONDS))
            stream.finishCapture(closed::countDown)
            device.allowQuiet.countDown()
            assertTrue(device.waitingForEnd.await(2, TimeUnit.SECONDS))
            device.allowEnd.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            await { writes.isNotEmpty() }
            assertEquals(listOf(339_200), writes)
        } finally {
            device.allowQuiet.countDown()
            device.allowEnd.countDown()
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }

    /** Startup quiet is unknown until PCM speech is observed, then silence follows the capture clock. */
    @Test
    fun silenceMillisStartsOnlyAfterActualCallerAudioSpeech() {
        val clock = FakeElapsedRealtime()
        val device = SpeechTrackingCaptureDevice()
        val capture =
            callerAudio(
                device = device,
                buffer =
                    ConversationDictationAudioChunkBuffer(
                        sessionId = 9L,
                        chunkBytes = 6_400,
                        maxBufferedBytes = 12_800,
                    ),
                elapsedRealtime = clock::now,
            )
        val stream = checkNotNull(capture.openProviderStream())
        try {
            assertTrue(stream.start())
            assertTrue(device.waitingForSpeech.await(2, TimeUnit.SECONDS))
            assertNull(capture.silenceMillis())

            device.allowSpeech.countDown()
            assertTrue(device.speechRecorded.await(2, TimeUnit.SECONDS))
            assertEquals(0L, capture.silenceMillis())

            clock.advance(3_000L)
            assertEquals(3_000L, capture.silenceMillis())
        } finally {
            device.allowSpeech.countDown()
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
        override fun read(
            target: ShortArray,
            waitForSamples: Boolean,
        ): Int {
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

    /** Holds before its first speech read and again after that read is fully observed by capture. */
    private class SpeechTrackingCaptureDevice : ConversationDictationAudioCaptureDevice {
        val waitingForSpeech = CountDownLatch(1)
        val allowSpeech = CountDownLatch(1)
        val speechRecorded = CountDownLatch(1)
        val allowEnd = CountDownLatch(1)
        private var reads = 0

        override val initialized: Boolean = true

        override val recording: Boolean = true

        override fun start() = Unit

        override fun read(
            target: ShortArray,
            waitForSamples: Boolean,
        ): Int =
            when (reads++) {
                0 -> {
                    waitingForSpeech.countDown()
                    check(allowSpeech.await(2, TimeUnit.SECONDS))
                    target.fill(1_000)
                    target.size
                }
                else -> {
                    speechRecorded.countDown()
                    check(allowEnd.await(2, TimeUnit.SECONDS))
                    0
                }
            }

        override fun stop() {
            allowEnd.countDown()
        }

        override fun release() {
            allowSpeech.countDown()
            allowEnd.countDown()
        }
    }

    /** Holds one read across the terminal action, then exposes more native-buffered tail reads. */
    private class FinishingReadCaptureDevice(
        private val availableReads: Int = 5,
    ) : ConversationDictationAudioCaptureDevice {
        val readStarted = CountDownLatch(1)
        val completeRead = CountDownLatch(1)
        val readCount = AtomicInteger(0)
        val readModes = CopyOnWriteArrayList<Boolean>()

        override val initialized: Boolean = true

        override val recording: Boolean = true

        override fun start() = Unit

        override fun read(
            target: ShortArray,
            waitForSamples: Boolean,
        ): Int {
            readModes += waitForSamples
            val readIndex = readCount.getAndIncrement()
            if (readIndex == 0) {
                readStarted.countDown()
                check(completeRead.await(2, TimeUnit.SECONDS))
            }
            if (readIndex > 0) {
                check(!waitForSamples) { "Completion must not wait for fresh microphone audio" }
                if (readIndex > availableReads) return 0
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

        override fun read(
            target: ShortArray,
            waitForSamples: Boolean,
        ): Int {
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
        private val finalRead: Boolean = false,
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

        override fun read(
            target: ShortArray,
            waitForSamples: Boolean,
        ): Int {
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
                    if (!finalRead) return 0
                    target.fill(1)
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

        override fun read(
            target: ShortArray,
            waitForSamples: Boolean,
        ): Int {
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
