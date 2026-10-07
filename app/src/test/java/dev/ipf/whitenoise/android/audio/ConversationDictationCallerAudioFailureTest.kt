package dev.ipf.whitenoise.android.audio

import android.os.Looper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCallerAudioFailureTest {
    /** A failed read preserves completed samples and reports before blocked native release. */
    @Test
    fun recorderFailuresRetainCompletedPcmWithoutRestartingTheMicrophone() {
        listOf(
            ReadFault(-6),
            ReadFault(0),
            ReadFault(-6, finishRequested = true),
            ReadFault(throws = true),
            ReadFault(throws = true, finishRequested = true),
            ReadFault(0, finishRequested = true),
        ).forEach(::verifyRecorderFailure)
    }

    private fun verifyRecorderFailure(fault: ReadFault) {
        val fixture = RecorderFaultFixture(fault)
        with(fixture) {
            try {
                assertTrue(capture.start())
                assertTrue(readEntered.await(2, TimeUnit.SECONDS))
                if (fault.finishRequested) capture.finish {}
                continueRead.countDown()
                assertTrue(releaseEntered.await(2, TimeUnit.SECONDS))
                assertEquals(1L, closed.count)
                assertTrue(failures.isEmpty())
                shadowOf(Looper.getMainLooper()).idle()
                val expected =
                    if (fault.result == 0 && fault.finishRequested && !fault.throws) {
                        emptyList()
                    } else {
                        listOf(ConversationDictationFailure.Unknown)
                    }
                assertEquals(expected, failures)
                val retained = checkNotNull(buffer.poll())
                assertArrayEquals(byteArrayOf(1, 0, 2, 0), retained.pcm)
                assertTrue(buffer.retry(retained.chunkId))
                assertTrue(capture.start())
                assertEquals(1, starts.get())
                assertEquals(1L, closed.count)
                assertFalse(capture.acknowledgeFailure(ConversationDictationCallerAudioFailure.CaptureFailed))
                continueRelease.countDown()
                assertTrue(closed.await(2, TimeUnit.SECONDS))
            } finally {
                continueRead.countDown()
                continueRelease.countDown()
                stream.cancel()
                stream.closeProviderEnd()
                capture.discard {}
            }
        }
    }

    private inner class RecorderFaultFixture(private val fault: ReadFault) {
        val readEntered = CountDownLatch(1)
        val continueRead = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        val continueRelease = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val starts = AtomicInteger(0)
        val reads = AtomicInteger(0)
        val failures = CopyOnWriteArrayList<ConversationDictationFailure>()
        val device =
            object : ConversationDictationAudioCaptureDevice {
                override val initialized = true
                override val recording = true
                override fun start() {
                    starts.incrementAndGet()
                }
                override fun stop() = Unit
                override fun release() {
                    releaseEntered.countDown()
                    check(continueRelease.await(5, TimeUnit.SECONDS))
                }
                override fun read(target: ShortArray, waitForSamples: Boolean): Int {
                    if (reads.incrementAndGet() == 1) {
                        readEntered.countDown()
                        check(continueRead.await(5, TimeUnit.SECONDS))
                        target[0] = 1
                        target[1] = 2
                        return 2
                    }
                    assertEquals(!fault.finishRequested, waitForSamples)
                    if (fault.throws) throw IllegalStateException("recorder died")
                    return fault.result
                }
            }
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 1L, chunkBytes = 8, maxBufferedBytes = 16)
        val capture = ConversationDictationCallerAudio(device, buffer)
        val listener = failureListener(failures)
        val stream =
            checkNotNull(
                capture.openProviderStream(
                    mainThreadDictationCallerAudioFailure(listener, capture::acknowledgeFailure),
                ),
            )

        init {
            capture.onCaptureClosed(closed::countDown)
        }
    }

    /** Native stop caused by force-close or discard explains its subsequent read exception. */
    @Test
    fun forcedClosureAndDiscardKeepInducedReadExceptionsQuiet() {
        listOf(false, true).forEach { discard ->
            val readEntered = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val failures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
            val device =
                object : ConversationDictationAudioCaptureDevice {
                    override val initialized = true
                    override val recording = true
                    override fun start() = Unit
                    override fun stop() = stopped.countDown()
                    override fun release() = Unit
                    override fun read(target: ShortArray, waitForSamples: Boolean): Int {
                        readEntered.countDown()
                        check(stopped.await(5, TimeUnit.SECONDS))
                        throw IllegalStateException("read interrupted by native stop")
                    }
                }
            val buffer = ConversationDictationAudioChunkBuffer(sessionId = 2L, chunkBytes = 8, maxBufferedBytes = 16)
            val capture = ConversationDictationCallerAudio(device, buffer)
            val stream = checkNotNull(capture.openProviderStream(failures::add))
            try {
                assertTrue(capture.start())
                assertTrue(readEntered.await(2, TimeUnit.SECONDS))
                if (discard) capture.discard(closed::countDown) else capture.forceFinish(closed::countDown)
                assertTrue(closed.await(2, TimeUnit.SECONDS))
                assertTrue(failures.isEmpty())
            } finally {
                stopped.countDown()
                stream.cancel()
                stream.closeProviderEnd()
                capture.discard {}
            }
        }
    }

    /** Posting to an expired generation never consumes the sticky capture failure. */
    @Test
    fun unacceptedQueuedFailureIsDeliveredToTheNextStreamUntilConsumed() {
        val finished = CountDownLatch(1)
        val device =
            object : ConversationDictationAudioCaptureDevice {
                override val initialized = true
                override val recording = true
                override fun start() = Unit
                override fun stop() = Unit
                override fun release() = finished.countDown()
                override fun read(target: ShortArray, waitForSamples: Boolean): Int = -6
            }
        val capture = ConversationDictationCallerAudio(
            device,
            ConversationDictationAudioChunkBuffer(sessionId = 3L, chunkBytes = 8, maxBufferedBytes = 16),
        )
        val oldFailures = CopyOnWriteArrayList<ConversationDictationCallerAudioFailure>()
        val old = checkNotNull(capture.openProviderStream(oldFailures::add))
        try {
            assertTrue(capture.start())
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(ConversationDictationCallerAudioFailure.CaptureFailed), oldFailures)
            old.cancel()
            old.closeProviderEnd()
            val nextFailures = CopyOnWriteArrayList<ConversationDictationFailure>()
            val next =
                checkNotNull(
                    capture.openProviderStream(
                        mainThreadDictationCallerAudioFailure(
                            failureListener(nextFailures), capture::acknowledgeFailure,
                        ),
                    ),
                )
            assertTrue(nextFailures.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(ConversationDictationFailure.Unknown), nextFailures)
            next.cancel()
            next.closeProviderEnd()
            val last = checkNotNull(capture.openProviderStream { throw AssertionError("failure already consumed") })
            last.cancel()
            last.closeProviderEnd()
        } finally {
            old.closeProviderEnd()
            capture.discard {}
        }
    }

    private fun failureListener(failures: MutableList<ConversationDictationFailure>) =
        object : ConversationDictationRecognitionListener {
            override fun onReady() = Unit
            override fun onEndOfSpeech() = Unit
            override fun onResult(transcript: String?) = Unit
            override fun onError(error: ConversationDictationFailure) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                failures.add(error)
            }
        }

    private data class ReadFault(
        val result: Int = -6,
        val throws: Boolean = false,
        val finishRequested: Boolean = false,
    )
}
