package dev.ipf.whitenoise.android.audio

import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.File

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

    @Test
    fun startFailureReleasesUnassignedPlayerAndAbandonsFocus() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        VoicePlaybackController.attach(context)

        assertTrue(requestFocus())
        val focusRequest = shadowAudioManager.lastAudioFocusRequest
        assertNotNull(focusRequest)
        setPlaybackState(
            VoicePlaybackController.PlaybackState(
                key = "voice-key",
                isPlaying = true,
                positionMs = 123,
                durationMs = 456,
            ),
        )
        val mediaPlayer = ThrowingStartMediaPlayer()

        assertFalse(startPreparedNewPlayer(mediaPlayer))

        assertTrue(mediaPlayer.released)
        assertNull(controllerField("focusRequest"))
        assertSame(focusRequest.audioFocusRequest, shadowAudioManager.lastAbandonedAudioFocusRequest)
        assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
    }

    @Test
    fun resumeStartFailureReleasesActivePlayerAndAbandonsFocus() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        VoicePlaybackController.attach(context)

        assertTrue(requestFocus())
        val focusRequest = shadowAudioManager.lastAudioFocusRequest
        assertNotNull(focusRequest)
        val mediaPlayer = ThrowingStartMediaPlayer()
        setControllerField("player", mediaPlayer)
        setControllerField("currentKey", "voice-key")
        setControllerField("currentOwnerKey", "old-owner")
        setPlaybackState(
            VoicePlaybackController.PlaybackState(
                key = "voice-key",
                isPlaying = false,
                positionMs = 123,
                durationMs = 456,
            ),
        )

        val result =
            runBlocking {
                VoicePlaybackController.play("voice-key", File("unused.amr"), ownerKey = "new-owner")
            }

        assertEquals(VoicePlaybackController.PlaybackStartResult.StartFailed, result)
        assertTrue(mediaPlayer.released)
        assertNull(controllerField("player"))
        assertNull(controllerField("currentKey"))
        assertNull(controllerField("currentOwnerKey"))
        assertNull(controllerField("focusRequest"))
        assertSame(focusRequest.audioFocusRequest, shadowAudioManager.lastAbandonedAudioFocusRequest)
        assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
    }

    // Hit the private focus path directly; public playback needs MediaPlayer file
    // setup and would obscure the focus bookkeeping this regression protects.
    private fun requestFocus(): Boolean {
        val method = VoicePlaybackController::class.java.getDeclaredMethod("requestFocus")
        method.isAccessible = true
        return method.invoke(VoicePlaybackController) as Boolean
    }

    private fun startPreparedNewPlayer(mediaPlayer: MediaPlayer): Boolean {
        val method =
            VoicePlaybackController::class.java.getDeclaredMethod(
                "startPreparedNewPlayer",
                MediaPlayer::class.java,
            )
        method.isAccessible = true
        return method.invoke(VoicePlaybackController, mediaPlayer) as Boolean
    }

    private fun controllerField(name: String): Any? {
        val field = VoicePlaybackController::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(VoicePlaybackController)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setPlaybackState(state: VoicePlaybackController.PlaybackState) {
        val field = VoicePlaybackController::class.java.getDeclaredField("_state")
        field.isAccessible = true
        val stateFlow =
            field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
        stateFlow.value = state
    }

    private fun setControllerField(
        name: String,
        value: Any?,
    ) {
        val field = VoicePlaybackController::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(VoicePlaybackController, value)
    }

    private class ThrowingStartMediaPlayer : MediaPlayer() {
        var released = false
            private set

        override fun start(): Unit = throw IllegalStateException("start failed")

        override fun release() {
            released = true
        }
    }
}
