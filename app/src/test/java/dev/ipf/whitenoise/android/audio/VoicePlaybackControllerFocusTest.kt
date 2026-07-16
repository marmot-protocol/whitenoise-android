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
        setAudioFocusOwnerField("audioManager", null)
        setAudioFocusOwnerField("focusRequest", null)
        setAudioFocusOwnerField("currentOwner", null)
        setAudioFocusOwnerField("onLoss", null)
        setAudioFocusOwnerField("onSurrender", null)
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
        assertNull(audioFocusOwnerField("focusRequest"))

        shadowAudioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        assertTrue(requestFocus())
        assertNotNull(audioFocusOwnerField("focusRequest"))
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
        assertNull(audioFocusOwnerField("focusRequest"))
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
        assertNull(audioFocusOwnerField("focusRequest"))
        assertSame(focusRequest.audioFocusRequest, shadowAudioManager.lastAbandonedAudioFocusRequest)
        assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
    }

    @Test
    fun transientLossRetainsFocusAndResumesTheInterruptedPlayerOnGain() {
        val context = RuntimeEnvironment.getApplication()
        VoicePlaybackController.attach(context)
        assertTrue(requestFocus())
        val heldFocusRequest = audioFocusOwnerField("focusRequest")
        assertNotNull(heldFocusRequest)
        val mediaPlayer = TrackingMediaPlayer()
        primeActivePlayer(mediaPlayer)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertFalse(mediaPlayer.playing)
        assertFalse(VoicePlaybackController.state.value.isPlaying)
        assertSame(heldFocusRequest, audioFocusOwnerField("focusRequest"))
        assertTrue(controllerField("resumeOnAudioFocusGain") as Boolean)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue(mediaPlayer.playing)
        assertTrue(VoicePlaybackController.state.value.isPlaying)
        assertSame(heldFocusRequest, audioFocusOwnerField("focusRequest"))
        assertFalse(controllerField("resumeOnAudioFocusGain") as Boolean)
    }

    @Test
    fun userPlayAfterMissingTransientGainRequestsFocusAgain() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        VoicePlaybackController.attach(context)
        assertTrue(requestFocus())
        val retainedRequest = shadowAudioManager.lastAudioFocusRequest
        val mediaPlayer = TrackingMediaPlayer()
        primeActivePlayer(mediaPlayer)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        val result =
            runBlocking {
                VoicePlaybackController.play("voice-key", File("unused.amr"), ownerKey = "new-owner")
            }

        assertEquals(VoicePlaybackController.PlaybackStartResult.Resumed, result)
        assertTrue(mediaPlayer.playing)
        assertFalse(controllerField("resumeOnAudioFocusGain") as Boolean)
        assertSame(retainedRequest.audioFocusRequest, shadowAudioManager.lastAbandonedAudioFocusRequest)
        assertTrue(shadowAudioManager.lastAudioFocusRequest !== retainedRequest)
    }

    @Test
    fun userPlayAfterTransientLossStaysPausedWhenFreshFocusIsDenied() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        VoicePlaybackController.attach(context)
        assertTrue(requestFocus())
        val mediaPlayer = TrackingMediaPlayer()
        primeActivePlayer(mediaPlayer)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowAudioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val result =
            runBlocking {
                VoicePlaybackController.play("voice-key", File("unused.amr"), ownerKey = "new-owner")
            }

        assertEquals(VoicePlaybackController.PlaybackStartResult.FocusDenied, result)
        assertFalse(mediaPlayer.playing)
        assertFalse(VoicePlaybackController.state.value.isPlaying)
        assertFalse(controllerField("resumeOnAudioFocusGain") as Boolean)
        assertNull(audioFocusOwnerField("focusRequest"))
    }

    @Test
    fun duckableLossLowersVolumeAndGainRestoresItWithoutRestarting() {
        val mediaPlayer = TrackingMediaPlayer()
        primeActivePlayer(mediaPlayer)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertTrue(mediaPlayer.playing)
        assertEquals(0.2f, mediaPlayer.leftVolume, 0f)
        assertEquals(0.2f, mediaPlayer.rightVolume, 0f)
        assertEquals(0, mediaPlayer.startCount)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(1f, mediaPlayer.leftVolume, 0f)
        assertEquals(1f, mediaPlayer.rightVolume, 0f)
        assertEquals(0, mediaPlayer.startCount)
    }

    @Test
    fun failedDuckFallsBackToTransientPause() {
        val mediaPlayer = TrackingMediaPlayer(failVolumeChange = true)
        primeActivePlayer(mediaPlayer)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertFalse(mediaPlayer.playing)
        assertFalse(VoicePlaybackController.state.value.isPlaying)
        assertTrue(controllerField("resumeOnAudioFocusGain") as Boolean)
    }

    @Test
    fun failedTransientPauseReleasesBrokenPlayerAndFocus() {
        val context = RuntimeEnvironment.getApplication()
        VoicePlaybackController.attach(context)
        assertTrue(requestFocus())
        val mediaPlayer = TrackingMediaPlayer(failPause = true)
        primeActivePlayer(mediaPlayer)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertTrue(mediaPlayer.released)
        assertNull(controllerField("player"))
        assertNull(audioFocusOwnerField("focusRequest"))
        assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
    }

    @Test
    fun failedUserPauseControlsReleaseBrokenPlayerAndFocus() {
        val context = RuntimeEnvironment.getApplication()
        listOf(
            TrackingMediaPlayer(failPlayingQuery = true),
            TrackingMediaPlayer(failPause = true),
        ).forEach { mediaPlayer ->
            VoicePlaybackController.attach(context)
            assertTrue(requestFocus())
            primeActivePlayer(mediaPlayer)

            VoicePlaybackController.pause()

            assertTrue(mediaPlayer.released)
            assertNull(controllerField("player"))
            assertNull(audioFocusOwnerField("focusRequest"))
            assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
        }
    }

    @Test
    fun failedFocusLossStateQueriesReleaseBrokenPlayerAndFocus() {
        val context = RuntimeEnvironment.getApplication()
        listOf(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        ).forEach { focusChange ->
            VoicePlaybackController.attach(context)
            assertTrue(requestFocus())
            val mediaPlayer = TrackingMediaPlayer(failPlayingQuery = true)
            primeActivePlayer(mediaPlayer)

            handleAudioFocusChange(focusChange)

            assertTrue(mediaPlayer.released)
            assertNull(controllerField("player"))
            assertNull(audioFocusOwnerField("focusRequest"))
            assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
        }
    }

    @Test
    fun failedVolumeRestoreReleasesBrokenPlayerAndFocus() {
        val context = RuntimeEnvironment.getApplication()
        val restoreActions: List<() -> Unit> =
            listOf(
                { handleAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN) },
                { VoicePlaybackController.pause() },
            )
        restoreActions.forEach { restoreAction ->
            VoicePlaybackController.attach(context)
            assertTrue(requestFocus())
            val mediaPlayer = TrackingMediaPlayer(failVolumeRestore = true)
            primeActivePlayer(mediaPlayer)

            handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertEquals(0.2f, mediaPlayer.leftVolume, 0f)

            restoreAction()

            assertTrue(mediaPlayer.released)
            assertNull(controllerField("player"))
            assertNull(audioFocusOwnerField("focusRequest"))
            assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
        }
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

    private fun handleAudioFocusChange(change: Int) {
        val method = VoicePlaybackController::class.java.getDeclaredMethod("handleAudioFocusChange", Integer.TYPE)
        method.isAccessible = true
        method.invoke(VoicePlaybackController, change)
    }

    private fun primeActivePlayer(mediaPlayer: TrackingMediaPlayer) {
        setControllerField("player", mediaPlayer)
        setControllerField("currentKey", "voice-key")
        setControllerField("currentOwnerKey", "owner")
        setPlaybackState(
            VoicePlaybackController.PlaybackState(
                key = "voice-key",
                isPlaying = true,
                positionMs = mediaPlayer.positionMs,
                durationMs = mediaPlayer.durationMs,
            ),
        )
    }

    private fun controllerField(name: String): Any? {
        val field = VoicePlaybackController::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(VoicePlaybackController)
    }

    private fun audioFocusOwnerField(name: String): Any? {
        val field = AudioFocusOwner::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(AudioFocusOwner)
    }

    private fun setAudioFocusOwnerField(
        name: String,
        value: Any?,
    ) {
        val field = AudioFocusOwner::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(AudioFocusOwner, value)
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

    private class TrackingMediaPlayer(
        private val failPause: Boolean = false,
        private val failVolumeChange: Boolean = false,
        private val failVolumeRestore: Boolean = false,
        private val failPlayingQuery: Boolean = false,
    ) : MediaPlayer() {
        var playing = true
        var released = false
        var leftVolume = 1f
        var rightVolume = 1f
        var startCount = 0
        val positionMs = 123
        val durationMs = 456

        override fun isPlaying(): Boolean {
            if (failPlayingQuery) throw IllegalStateException("isPlaying failed")
            return playing
        }

        override fun pause() {
            if (failPause) throw IllegalStateException("pause failed")
            playing = false
        }

        override fun start() {
            startCount += 1
            playing = true
        }

        override fun stop() {
            playing = false
        }

        override fun getCurrentPosition(): Int = positionMs

        override fun getDuration(): Int = durationMs

        override fun setVolume(
            leftVolume: Float,
            rightVolume: Float,
        ) {
            if (failVolumeChange || (failVolumeRestore && leftVolume == 1f && rightVolume == 1f)) {
                throw IllegalStateException("setVolume failed")
            }
            this.leftVolume = leftVolume
            this.rightVolume = rightVolume
        }

        override fun release() {
            released = true
        }
    }
}
