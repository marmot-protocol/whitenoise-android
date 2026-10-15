package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCallerAudioReplayTest {
    /** The real feeder replays the supplied quiet-tail shape without reacquiring capture. */
    @Test
    fun emptyQuietTailReplaysExactRecognizedContextThroughProviderPipe() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 27L)
        val writes = CopyOnWriteArrayList<ByteArray>()
        val capture = replayCapture(buffer, writes)
        repeat(4) { index ->
            val pcm = ByteArray(320_000) { (index + 1).toByte() }
            assertTrue(buffer.append(pcm, pcm.size, hasSpeech = true))
            assertTrue(buffer.sealCurrentIfAtLeast(320_000))
        }
        val quietTail = ByteArray(93_436) { 1 }
        assertTrue(buffer.append(quietTail, quietTail.size, hasSpeech = true))
        val closed = CountDownLatch(1)
        capture.forceFinish(closed::countDown)
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        try {
            repeat(4) { index ->
                val stream = checkNotNull(capture.openProviderStream())
                val fed = CountDownLatch(1)
                stream.onFeedClosed(fed::countDown)
                assertTrue(stream.start())
                assertTrue(fed.await(2, TimeUnit.SECONDS))
                assertTrue(stream.fullyFed())
                assertNotNull(stream.acknowledge("part ${index + 1}"))
                stream.closeProviderEnd()
            }
            writes.clear()
            val isolated = checkNotNull(capture.openProviderStream())
            val firstFeed = CountDownLatch(1)
            isolated.onFeedClosed(firstFeed::countDown)
            assertTrue(isolated.start())
            assertTrue(firstFeed.await(2, TimeUnit.SECONDS))
            assertArrayEquals(quietTail, writes.single())
            assertTrue(isolated.retryWithContext())
            isolated.closeProviderEnd()

            writes.clear()
            val replay = checkNotNull(capture.openProviderStream())
            val replayFed = CountDownLatch(1)
            replay.onFeedClosed(replayFed::countDown)
            assertTrue(replay.start())
            assertTrue(replayFed.await(2, TimeUnit.SECONDS))
            assertEquals(2, writes.size)
            assertArrayEquals(ByteArray(320_000) { 4 }, writes[0])
            assertArrayEquals(quietTail, writes[1])
            assertTrue(replay.fullyFed())
            assertNotNull(replay.acknowledge("part four, after the bridge"))
            assertFalse(capture.hasPending())
            replay.closeProviderEnd()
        } finally {
            capture.discard {}
        }
    }

    private fun replayCapture(
        buffer: ConversationDictationAudioChunkBuffer,
        writes: CopyOnWriteArrayList<ByteArray>,
    ): ConversationDictationCallerAudio =
        ConversationDictationCallerAudio(
            device = object : ConversationDictationAudioCaptureDevice {
                override val initialized = true
                override val recording = true
                override fun start() = Unit
                override fun read(target: ShortArray, waitForSamples: Boolean): Int = 0
                override fun stop() = Unit
                override fun release() = Unit
            },
            buffer = buffer,
            pipeWriter = ConversationDictationAudioPipeWriter { _, source, offset, length ->
                writes += source.copyOfRange(offset, offset + length)
                length
            },
        )
}
