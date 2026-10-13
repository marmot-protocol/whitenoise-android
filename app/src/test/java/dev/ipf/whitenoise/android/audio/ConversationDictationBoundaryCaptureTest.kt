package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Boundary regressions exercise the real capture and feeder with the shared recorder fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationBoundaryCaptureTest {
    /** Stop after a quiet boundary includes the native drain in the same contextual provider request. */
    @Test
    fun finishJustAfterQuietBoundaryFeedsOneContextualRequestIncludingNativeDrain() {
        val clock = ConversationDictationCallerAudioTest.FakeElapsedRealtime()
        val device = ConversationDictationCallerAudioTest.QuietGapCaptureDevice(clock, finalRead = true)
        val writes = CopyOnWriteArrayList<Int>()
        val buffer =
            ConversationDictationAudioChunkBuffer(
                sessionId = 9L,
                minimumTailBytes = CALLER_AUDIO_MINIMUM_TAIL_BYTES,
            )
        val capture =
            ConversationDictationCallerAudio(
                device = device,
                buffer = buffer,
                pipeWriter = ConversationDictationAudioPipeWriter { _, _, _, length -> length.also(writes::add) },
                elapsedRealtime = clock::now,
            )
        val stream = checkNotNull(capture.openProviderStream())
        val closed = CountDownLatch(1)
        val fed = CountDownLatch(1)
        stream.onFeedClosed(fed::countDown)
        try {
            assertTrue(stream.start())
            assertTrue(device.waitingForQuiet.await(2, TimeUnit.SECONDS))
            device.allowQuiet.countDown()
            assertTrue(device.waitingForEnd.await(2, TimeUnit.SECONDS))
            assertTrue(writes.isEmpty())
            stream.finishCapture(closed::countDown)
            device.allowEnd.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(fed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(355_200), writes)
            assertEquals(true, stream.containsSpeech())
            assertTrue(stream.fullyFed())
            assertTrue(stream.isFinalChunk())
            assertTrue(stream.acknowledge())
            assertFalse(capture.hasPending())
        } finally {
            device.allowQuiet.countDown()
            device.allowEnd.countDown()
            stream.cancel()
            stream.closeProviderEnd()
            capture.discard {}
        }
    }
}
