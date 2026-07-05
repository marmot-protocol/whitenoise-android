package dev.ipf.whitenoise.android.audio

import android.media.AudioManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoicePlaybackControllerFocusTest {
    @After
    fun tearDown() {
        VoicePlaybackController.stop()
        setControllerField("audioManager", null)
        setControllerField("focusRequest", null)
    }

    @Test
    fun requestFocusReusesHeldRequestUntilAbandoned() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        VoicePlaybackController.attach(context)

        assertTrue(requestFocus())
        val firstFocusRequest = shadowAudioManager.lastAudioFocusRequest
        assertNotNull(firstFocusRequest)

        assertTrue(requestFocus())

        assertSame(firstFocusRequest, shadowAudioManager.lastAudioFocusRequest)
        VoicePlaybackController.stop()
        assertSame(firstFocusRequest.audioFocusRequest, shadowAudioManager.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun failedFocusRequestIsNotRememberedAsHeldFocus() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        VoicePlaybackController.attach(context)

        shadowAudioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        assertFalse(requestFocus())
        assertNull(controllerField("focusRequest"))

        shadowAudioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        assertTrue(requestFocus())
        assertNotNull(controllerField("focusRequest"))
    }

    // Hit the private focus path directly; public playback needs MediaPlayer file
    // setup and would obscure the focus bookkeeping this regression protects.
    private fun requestFocus(): Boolean {
        val method = VoicePlaybackController::class.java.getDeclaredMethod("requestFocus")
        method.isAccessible = true
        return method.invoke(VoicePlaybackController) as Boolean
    }

    private fun controllerField(name: String): Any? {
        val field = VoicePlaybackController::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(VoicePlaybackController)
    }

    private fun setControllerField(
        name: String,
        value: Any?,
    ) {
        val field = VoicePlaybackController::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(VoicePlaybackController, value)
    }
}
