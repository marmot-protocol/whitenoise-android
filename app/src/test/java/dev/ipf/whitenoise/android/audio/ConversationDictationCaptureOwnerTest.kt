package dev.ipf.whitenoise.android.audio

import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCaptureOwnerTest {
    /** A parked recording releases the active slot without losing or duplicating its sealed sample bytes. */
    @Test
    fun parkedCaptureRequiresNativeClosureAndRestoresItsExactBuffer() {
        val buffer = ConversationDictationAudioChunkBuffer(sessionId = 17L, chunkBytes = 4, maxBufferedBytes = 8)
        val capture = ConversationDictationCallerAudio(FakeCaptureDevice(shortArrayOf(1, 2)), buffer)
        val replacement =
            ConversationDictationCallerAudio(FakeCaptureDevice(), ConversationDictationAudioChunkBuffer(18L))
        var creates = 0
        val owner = ConversationDictationCaptureOwner { if (creates++ == 0) capture else replacement }
        assertTrue(owner.acquire() === capture)
        assertNull(owner.park())
        assertTrue(capture.start())
        await { capture.closed }
        val kept = checkNotNull(owner.park())
        assertFalse(owner.hasPending())
        assertTrue(buffer.hasPending)
        assertTrue(owner.acquire() === replacement)
        assertFalse(kept.restore())
        var replacementReleased = false
        owner.discard { replacementReleased = true }
        await {
            shadowOf(Looper.getMainLooper()).idle()
            replacementReleased
        }
        assertTrue(kept.restore())
        assertTrue(owner.acquire() === capture)
        assertFalse(kept.restore())
        val chunk = checkNotNull(buffer.poll())
        assertTrue(chunk.pcm.contentEquals(byteArrayOf(1, 0, 2, 0)))
        assertTrue(buffer.retry(chunk.chunkId))
        owner.discard {}
        assertFalse(buffer.hasPending)
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(predicate())
    }

    private class FakeCaptureDevice(
        private var samples: ShortArray? = null,
    ) : ConversationDictationAudioCaptureDevice {
        override val initialized = true
        override val recording = true

        override fun start() = Unit

        override fun read(
            target: ShortArray,
            waitForSamples: Boolean,
        ): Int {
            val next = samples ?: return 0
            samples = null
            next.copyInto(target)
            return next.size
        }

        override fun stop() = Unit

        override fun release() = Unit
    }
}
