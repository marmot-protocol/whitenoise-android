package dev.ipf.whitenoise.android.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDictationAudioFocusLeaseTest {
    private val callbacks = mutableListOf<(Int) -> Unit>()
    private val surrenderCallbacks = mutableListOf<() -> Unit>()
    private val mainQueue = mutableListOf<() -> Unit>()
    private var requestCount = 0
    private var abandonCount = 0
    private var captureEndCount = 0
    private var grant = true

    private val lease =
        ConversationDictationAudioFocusLease(
            requestFocus = { onChange, onSurrender ->
                requestCount++
                callbacks += onChange
                surrenderCallbacks += onSurrender
                grant
            },
            abandonFocus = { abandonCount++ },
            postToMain = { mainQueue += it },
            endCapture = { captureEndCount++ },
        )

    @Test
    fun deniedFocusNeverOwnsOrAbandonsARequest() {
        grant = false
        assertFalse(lease.acquire())
        lease.release()
        assertEquals(1, requestCount)
        assertEquals(0, abandonCount)
        callbacks.single()(AudioManager.AUDIOFOCUS_LOSS)
        drainMain()
        assertEquals(0, captureEndCount)
    }

    @Test
    fun releaseIsOnceOnlyAndLateCallbacksCannotEndLaterCapture() {
        assertTrue(lease.acquire())
        lease.release()
        lease.release()
        assertEquals(1, abandonCount)

        assertTrue(lease.acquire())
        callbacks.first()(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        surrenderCallbacks.first()()
        drainMain()
        assertEquals(0, captureEndCount)

        callbacks.last()(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        callbacks.last()(AudioManager.AUDIOFOCUS_GAIN)
        drainMain()
        assertEquals(0, captureEndCount)
        callbacks.last()(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        callbacks.last()(AudioManager.AUDIOFOCUS_LOSS)
        drainMain()
        assertEquals(1, captureEndCount)
        lease.release()
        assertEquals(2, abandonCount)
    }

    @Test
    fun surrenderAndPendingLossDoNotCancelAfterCaptureCloses() {
        assertTrue(lease.acquire())
        assertFalse(lease.acquire())
        assertEquals(1, requestCount)

        surrenderCallbacks.single()()
        assertEquals(1, mainQueue.size)
        lease.release()
        drainMain()
        assertEquals(0, captureEndCount)
        assertEquals(1, abandonCount)
    }

    private fun drainMain() {
        val queued = mainQueue.toList()
        mainQueue.clear()
        queued.forEach { it() }
    }
}
