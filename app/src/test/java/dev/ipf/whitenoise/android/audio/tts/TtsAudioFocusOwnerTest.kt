package dev.ipf.whitenoise.android.audio.tts

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsAudioFocusOwnerTest {
    /** Protects the historical full-focus behavior outside media-mix mode. */
    @Test
    fun ordinaryModeRequestsFullGain() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AudioManager::class.java)
        val owner = TtsAudioFocusOwner(context)

        assertTrue(owner.acquire(TtsAudioFocusMode.Full, {}, {}))

        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN,
            shadowOf(manager).lastAudioFocusRequest.audioFocusRequest.focusGain,
        )
        owner.release()
    }

    /** Keeps MediaMix from pausing peer players through Android audio focus. */
    @Test
    fun mediaMixModeDoesNotRequestOrAbandonAudioFocus() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AudioManager::class.java)
        val owner = TtsAudioFocusOwner(context)

        assertTrue(owner.acquire(TtsAudioFocusMode.MediaMix, {}, {}))

        assertNull(shadowOf(manager).lastAudioFocusRequest)
        owner.release()
        assertNull(shadowOf(manager).lastAbandonedAudioFocusRequest)
    }

    /** Makes every callback after permanent focus surrender inert. */
    @Test
    fun permanentLossInvalidatesTheReleasedRequestCallbacks() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AudioManager::class.java)
        val owner = TtsAudioFocusOwner(context)
        var transientLosses = 0
        var permanentLosses = 0
        assertTrue(
            owner.acquire(
                TtsAudioFocusMode.Full,
                onFocusLoss = { transientLosses += 1 },
                onOwnerSurrender = { permanentLosses += 1 },
            ),
        )
        val listener = shadowOf(manager).lastAudioFocusRequest.listener

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertEquals(1, permanentLosses)
        assertEquals(0, transientLosses)
    }
}
