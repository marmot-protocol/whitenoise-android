package dev.ipf.whitenoise.android.audio

import android.media.AudioManager
import android.os.Looper
import dev.ipf.whitenoise.android.state.StalenessGuard
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoicePlaybackControllerFocusTest {
    /** Verifies dictation invalidates a clip that is still preparing without retaining a player. */
    @Test
    fun dictationInterruptionInvalidatesPlaybackStillPreparingWithoutAnActivePlayer() {
        val requests = controllerField("playbackRequests") as StalenessGuard
        val preparing = requests.capture()

        assertNull(VoicePlaybackController.pauseForInterruption())

        assertFalse(requests.isCurrent(preparing))
    }

    /** Verifies a later explicit playback action makes the dictation interruption stale. */
    @Test
    fun laterUserPlaybackActionsInvalidateTheSavedDictationInterruption() {
        val context = RuntimeEnvironment.getApplication()
        VoicePlaybackController.attach(context)
        val mediaPlayer = TrackingVoicePlayer()
        primeActivePlayer(mediaPlayer)
        val interruption = VoicePlaybackController.pauseForInterruption()
        assertNotNull(interruption)

        val userResume =
            runBlocking {
                VoicePlaybackController.play("voice-key", File("unused.amr"), ownerKey = "owner")
            }
        VoicePlaybackController.pause()

        assertEquals(VoicePlaybackController.PlaybackStartResult.Resumed, userResume)
        assertFalse(VoicePlaybackController.resumeInterrupted(checkNotNull(interruption)))
        assertEquals(1, mediaPlayer.startCount)
    }

    /** Persistent controls resume the retained player and keep exact source ownership across pause. */
    @Test fun shellPauseResumeRetainsPlayerAndSource() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        val source = VoicePlaybackSource("personal", "chat", "message", "Maya")
        setPlaybackState(VoicePlaybackController.state.value.copy(source = source, sessionId = 42, ready = true))
        assertTrue(VoicePlaybackController.setSessionPlaying(42, false))
        assertFalse(player.playing)
        assertTrue(VoicePlaybackController.setSessionPlaying(42, true))
        assertEquals(1, player.startCount)
        assertEquals(source, VoicePlaybackController.state.value.source)
        assertEquals(player.positionMs, VoicePlaybackController.state.value.positionMs)
    }

    /** A retained player can discover duration after preparation; shell resume refreshes that display metadata. */
    @Test fun shellResumeRefreshesLateDurationAndPreservesItAcrossUnavailableReports() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        setPlaybackState(VoicePlaybackController.state.value.copy(sessionId = 42, ready = true, durationMs = 0))
        VoicePlaybackController.pause()
        player.durationMs = 900
        assertTrue(VoicePlaybackController.setSessionPlaying(42, true))
        assertEquals(900, VoicePlaybackController.state.value.durationMs)
        VoicePlaybackController.pause()
        player.durationMs = 0
        assertTrue(VoicePlaybackController.setSessionPlaying(42, true))
        assertEquals(900, VoicePlaybackController.state.value.durationMs)
        VoicePlaybackController.pause()
        player.failDurationQuery = true
        assertTrue(VoicePlaybackController.setSessionPlaying(42, true))
        assertEquals(900, VoicePlaybackController.state.value.durationMs)
        assertFalse(player.released)
    }

    /** A stale rendered strip cannot pause, resume or stop its successor. */
    @Test fun replacedShellSessionRejectsEveryOldControl() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        setPlaybackState(VoicePlaybackController.state.value.copy(sessionId = 43, ready = true))
        assertFalse(VoicePlaybackController.setSessionPlaying(42, false))
        assertFalse(VoicePlaybackController.setSessionPlaying(42, true))
        VoicePlaybackController.stopSession(42)
        assertTrue(player.playing)
        assertFalse(player.released)
        VoicePlaybackController.stopSession(43)
        assertTrue(player.released)
        assertNull(VoicePlaybackController.state.value.source)
    }

    /** Banner seeks move the retained player by the requested step, clamp to the clip and keep a pause paused. */
    @Test fun shellSeekClampsToTheClipAndPreservesPause() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        setPlaybackState(VoicePlaybackController.state.value.copy(sessionId = 42, ready = true))
        assertTrue(VoicePlaybackController.setSessionPlaying(42, false))

        assertTrue(VoicePlaybackController.seekSessionBy(42, 10_000))
        assertEquals(player.durationMs, player.seekedToMs)
        assertEquals(player.durationMs, VoicePlaybackController.state.value.positionMs)
        assertTrue(VoicePlaybackController.seekSessionBy(42, -10_000))
        assertEquals(0, player.seekedToMs)
        assertEquals(0, VoicePlaybackController.state.value.positionMs)
        assertFalse(player.playing)
        assertFalse(VoicePlaybackController.state.value.isPlaying)
        assertEquals(0, player.startCount)
    }

    /** A codec reporting no duration falls back to the known clip length, and with none known the seek is skipped. */
    @Test fun shellSeekUsesTheKnownLengthWhenTheCodecReportsNone() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        player.durationMs = 0
        setPlaybackState(VoicePlaybackController.state.value.copy(sessionId = 42, ready = true, durationMs = 900))

        assertTrue(VoicePlaybackController.seekSessionBy(42, 10_000))
        assertEquals(900, player.seekedToMs)
        assertEquals(900, VoicePlaybackController.state.value.durationMs)

        player.seekedToMs = null
        setPlaybackState(VoicePlaybackController.state.value.copy(positionMs = 123, durationMs = 0))
        assertFalse(VoicePlaybackController.seekSessionBy(42, 10_000))
        assertEquals(null, player.seekedToMs)
        assertEquals(123, VoicePlaybackController.state.value.positionMs)
    }

    /** Quick repeated taps build on the seek still in flight instead of re-reading the stale playhead. */
    @Test fun shellSeeksAccumulateWhileTheCodecIsStillSeeking() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        setPlaybackState(VoicePlaybackController.state.value.copy(sessionId = 42, ready = true))

        assertTrue(VoicePlaybackController.seekSessionBy(42, 100))
        assertTrue(VoicePlaybackController.seekSessionBy(42, 100))

        assertEquals(player.positionMs + 200, player.seekedToMs)
        assertEquals(player.positionMs + 200, VoicePlaybackController.state.value.positionMs)
    }

    /** An earlier queued seek completing keeps the later target, so a third quick tap still advances once more. */
    @Test fun earlierSeekCompletionKeepsTheLaterPendingTarget() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        setPlaybackState(VoicePlaybackController.state.value.copy(sessionId = 42, ready = true, durationMs = 5_000))
        player.durationMs = 5_000

        assertTrue(VoicePlaybackController.seekSessionBy(42, 1_000))
        assertTrue(VoicePlaybackController.seekSessionBy(42, 1_000))
        player.positionMs = 1_123
        settlePendingSeek(player)
        assertTrue(VoicePlaybackController.seekSessionBy(42, 1_000))
        assertEquals(3_123, player.seekedToMs)

        player.positionMs = 3_123
        settlePendingSeek(player)
        player.positionMs = 3_500
        assertTrue(VoicePlaybackController.seekSessionBy(42, 1_000))
        assertEquals(4_500, player.seekedToMs)
    }

    /** An off-target completion cannot pin a stale target: after the tap window the player's position wins again. */
    @Test fun missedSettleExpiresSoLaterSeeksStartFromThePlayhead() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        player.durationMs = 120_000
        setPlaybackState(VoicePlaybackController.state.value.copy(sessionId = 42, ready = true, durationMs = 120_000))

        assertTrue(VoicePlaybackController.seekSessionBy(42, 10_000))
        player.positionMs = 10_723
        settlePendingSeek(player)
        player.positionMs = 40_000
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))

        assertTrue(VoicePlaybackController.seekSessionBy(42, -10_000))
        assertEquals(30_000, player.seekedToMs)
    }

    /** A seek the codec rejects reports failure and leaves the published playhead where it was. */
    @Test fun rejectedShellSeekReportsFailureAndKeepsThePlayhead() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer(failSeek = true)
        primeActivePlayer(player)
        setPlaybackState(VoicePlaybackController.state.value.copy(sessionId = 42, ready = true))

        assertFalse(VoicePlaybackController.seekSessionBy(42, 100))
        assertEquals(player.positionMs, VoicePlaybackController.state.value.positionMs)
    }

    /** A stale banner cannot seek or change the speed of the session that replaced it. */
    @Test fun replacedShellSessionRejectsSeekAndSpeed() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        setControllerField("currentSpeed", 1f)
        setPlaybackState(VoicePlaybackController.state.value.copy(sessionId = 43, ready = true, speed = 1f))

        assertFalse(VoicePlaybackController.seekSessionBy(42, 10_000))
        assertNull(VoicePlaybackController.cycleSessionSpeed(42))
        assertNull(player.seekedToMs)
        assertEquals(1f, VoicePlaybackController.state.value.speed)

        assertEquals(1.5f, VoicePlaybackController.cycleSessionSpeed(43))
        assertEquals(1.5f, VoicePlaybackController.state.value.speed)
    }

    /** Equal attachment keys under another account must prepare new bytes instead of resuming the old player. */
    @Test fun sameKeyDifferentAccountDoesNotResumeCapturedAudio() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        val source = VoicePlaybackSource("personal", "chat", "message", "Maya")
        setPlaybackState(VoicePlaybackController.state.value.copy(source = source, sessionId = 42, ready = true))
        val freshFile = File("missing-voice-note.amr")
        val preparations = CopyOnWriteArrayList<File>()
        val retiredBeforePrepare = AtomicBoolean()
        setControllerField(
            "playerFactory",
            VoicePlaybackPlayer.Factory { _, file ->
                preparations += file
                retiredBeforePrepare.set(player.released)
                throw IOException("Synthetic new-account preparation refusal")
            },
        )
        val result =
            runBlocking {
                VoicePlaybackController.play(
                    "voice-key",
                    freshFile,
                    source = source.copy(accountRef = "work"),
                )
            }
        assertEquals(VoicePlaybackController.PlaybackStartResult.PrepareFailed, result)
        assertEquals(listOf(freshFile), preparations)
        assertTrue(retiredBeforePrepare.get())
        assertNull(controllerField("player"))
        assertEquals(0, player.startCount)
        assertTrue(player.released)
        assertNull(VoicePlaybackController.state.value.source)
    }

    /** Display-name refreshes cannot replace the same account/message player or reset its playback position. */
    @Test fun renamedSourceResumesSamePlayerAndRefreshesTitle() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        val source = VoicePlaybackSource("personal", "chat", "message", "Before")
        setPlaybackState(VoicePlaybackController.state.value.copy(source = source, sessionId = 42, ready = true))
        VoicePlaybackController.pause()
        val result =
            runBlocking {
                VoicePlaybackController.play("voice-key", File("unused.amr"), source = source.copy(title = "After"))
            }
        assertEquals(VoicePlaybackController.PlaybackStartResult.Resumed, result)
        assertEquals(42L, VoicePlaybackController.state.value.sessionId)
        assertEquals(
            "After",
            VoicePlaybackController.state.value.source
                ?.title,
        )
        assertFalse(player.released)
        assertEquals(1, player.startCount)
    }

    /** New sources prepare on IO through the parser-free seam and retain the adopted source owner. */
    @Test
    fun newVoiceSourceUsesOffMainFactoryAndRetainsItsPlayer() {
        val context = RuntimeEnvironment.getApplication()
        VoicePlaybackController.attach(context)
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val player = TrackingVoicePlayer()
        var preparedOffMain = false
        var preparedFile: File? = null
        val file = File("isolated-voice.amr")
        setControllerField(
            "playerFactory",
            VoicePlaybackPlayer.Factory { _, selected ->
                preparedOffMain = Looper.myLooper() != Looper.getMainLooper()
                preparedFile = selected
                player
            },
        )

        val result = runBlocking { VoicePlaybackController.play("new-source", file, ownerKey = "owner") }

        assertEquals(VoicePlaybackController.PlaybackStartResult.Started, result)
        assertTrue(preparedOffMain)
        assertSame(file, preparedFile)
        assertSame(player, controllerField("player"))
        assertEquals(1, player.startCount)
        assertEquals(player.durationMs, VoicePlaybackController.state.value.durationMs)
    }

    /** Changing speed preserves paused intent and exact retained source identity. */
    @Test
    fun pausedVoiceSpeedChangeDoesNotResumeOrReplaceItsPlayer() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)
        VoicePlaybackController.pause()

        assertEquals(1.5f, VoicePlaybackController.cycleSpeed())

        assertEquals(1.5f, player.appliedSpeed)
        assertFalse(player.playing)
        assertEquals(0, player.startCount)
        assertSame(player, controllerField("player"))
    }

    /** The controller applies speed without treating active buffering intent as a pause. */
    @Test
    fun playingVoiceSpeedChangePreservesPlaybackIntent() {
        VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
        val player = TrackingVoicePlayer()
        primeActivePlayer(player)

        assertEquals(1.5f, VoicePlaybackController.cycleSpeed())

        assertEquals(1.5f, player.appliedSpeed)
        assertTrue(player.playing)
        assertEquals(0, player.startCount)
    }

    /** Stops playback and clears reflected focus ownership so later fixtures cannot inherit a held request. */
    @After
    fun tearDown() {
        VoicePlaybackController.stop()
        setControllerField("playerFactory", RawVoicePlaybackPlayer.Factory)
        setControllerField("currentSpeed", 1f)
        setAudioFocusOwnerField("audioManager", null)
        setAudioFocusOwnerField("focusRequest", null)
        setAudioFocusOwnerField("currentOwner", null)
        setAudioFocusOwnerField("onFocusChange", null)
        setAudioFocusOwnerField("onSurrender", null)
    }

    /** Repeated play attempts reuse held focus; explicit abandonment requires a fresh Android request. */
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

    /** Denied focus cannot leave a cached handle that falsely authorizes a later play. */
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

    /** A player that fails before assignment is released together with its focus ownership. */
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
        val mediaPlayer = ThrowingStartVoicePlayer()

        assertFalse(startPreparedNewPlayer(mediaPlayer))

        assertTrue(mediaPlayer.released)
        assertNull(audioFocusOwnerField("focusRequest"))
        assertSame(focusRequest.audioFocusRequest, shadowAudioManager.lastAbandonedAudioFocusRequest)
        assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
    }

    /** Failed resume retires the active player and prevents its old owner from retaining audio focus. */
    @Test
    fun resumeStartFailureReleasesActivePlayerAndAbandonsFocus() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        VoicePlaybackController.attach(context)

        assertTrue(requestFocus())
        val focusRequest = shadowAudioManager.lastAudioFocusRequest
        assertNotNull(focusRequest)
        val mediaPlayer = ThrowingStartVoicePlayer()
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

    /** Temporary interruption retains the same focus handle and resumes only the interrupted player. */
    @Test
    fun transientLossRetainsFocusAndResumesTheInterruptedPlayerOnGain() {
        val context = RuntimeEnvironment.getApplication()
        VoicePlaybackController.attach(context)
        assertTrue(requestFocus())
        val heldFocusRequest = audioFocusOwnerField("focusRequest")
        assertNotNull(heldFocusRequest)
        val mediaPlayer = TrackingVoicePlayer()
        primeActivePlayer(mediaPlayer)

        audioFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertFalse(mediaPlayer.playing)
        assertFalse(VoicePlaybackController.state.value.isPlaying)
        assertSame(heldFocusRequest, audioFocusOwnerField("focusRequest"))
        assertTrue(controllerField("resumeOnAudioFocusGain") as Boolean)

        audioFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue(mediaPlayer.playing)
        assertTrue(VoicePlaybackController.state.value.isPlaying)
        assertSame(heldFocusRequest, audioFocusOwnerField("focusRequest"))
        assertFalse(controllerField("resumeOnAudioFocusGain") as Boolean)
    }

    /** An explicit play after a missing focus-gain callback requests fresh authorization. */
    @Test
    fun userPlayAfterMissingTransientGainRequestsFocusAgain() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        VoicePlaybackController.attach(context)
        assertTrue(requestFocus())
        val retainedRequest = shadowAudioManager.lastAudioFocusRequest
        val mediaPlayer = TrackingVoicePlayer()
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

    /** A denied explicit play cannot bypass the paused state left by transient interruption. */
    @Test
    fun userPlayAfterTransientLossStaysPausedWhenFreshFocusIsDenied() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        VoicePlaybackController.attach(context)
        assertTrue(requestFocus())
        val mediaPlayer = TrackingVoicePlayer()
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

    /** Ducking changes volume without restarting playback; gain restores the original level. */
    @Test
    fun duckableLossLowersVolumeAndGainRestoresItWithoutRestarting() {
        val mediaPlayer = TrackingVoicePlayer()
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

    /** A volume-control failure pauses the clip while retaining its legitimate gain-resume intent. */
    @Test
    fun failedDuckFallsBackToTransientPause() {
        val mediaPlayer = TrackingVoicePlayer(failVolumeChange = true)
        primeActivePlayer(mediaPlayer)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertFalse(mediaPlayer.playing)
        assertFalse(VoicePlaybackController.state.value.isPlaying)
        assertTrue(controllerField("resumeOnAudioFocusGain") as Boolean)
    }

    /** A player that cannot pause during focus loss is retired rather than left playing without control. */
    @Test
    fun failedTransientPauseReleasesBrokenPlayerAndFocus() {
        val context = RuntimeEnvironment.getApplication()
        VoicePlaybackController.attach(context)
        assertTrue(requestFocus())
        val mediaPlayer = TrackingVoicePlayer(failPause = true)
        primeActivePlayer(mediaPlayer)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertTrue(mediaPlayer.released)
        assertNull(controllerField("player"))
        assertNull(audioFocusOwnerField("focusRequest"))
        assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
    }

    /** A failed explicit pause releases the broken player and abandons its focus request. */
    @Test
    fun failedUserPauseControlsReleaseBrokenPlayerAndFocus() {
        val context = RuntimeEnvironment.getApplication()
        listOf(
            TrackingVoicePlayer(failPlayingQuery = true),
            TrackingVoicePlayer(failPause = true),
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

    /** Even a failing playback-state query during focus loss must release player and focus ownership. */
    @Test
    fun failedFocusLossStateQueriesReleaseBrokenPlayerAndFocus() {
        val context = RuntimeEnvironment.getApplication()
        listOf(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        ).forEach { focusChange ->
            VoicePlaybackController.attach(context)
            assertTrue(requestFocus())
            val mediaPlayer = TrackingVoicePlayer(failPlayingQuery = true)
            primeActivePlayer(mediaPlayer)

            handleAudioFocusChange(focusChange)

            assertTrue(mediaPlayer.released)
            assertNull(controllerField("player"))
            assertNull(audioFocusOwnerField("focusRequest"))
            assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
        }
    }

    /** A failed gain-volume restoration retires the player instead of leaving an unmanageable active clip. */
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
            val mediaPlayer = TrackingVoicePlayer(failVolumeRestore = true)
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

    // Hit the private focus path directly; public playback needs isolated file
    // setup and would obscure the focus bookkeeping this regression protects.

    /** Invokes the production controller's private focus boundary against Robolectric's AudioManager. */
    private fun requestFocus(): Boolean {
        val method = VoicePlaybackController::class.java.getDeclaredMethod("requestFocus")
        method.isAccessible = true
        return method.invoke(VoicePlaybackController) as Boolean
    }

    /** Exercises production player adoption with the supplied failure-capable test player. */
    private fun startPreparedNewPlayer(mediaPlayer: VoicePlaybackPlayer): Boolean {
        val method =
            VoicePlaybackController::class.java.getDeclaredMethod(
                "startPreparedNewPlayer",
                VoicePlaybackPlayer::class.java,
            )
        method.isAccessible = true
        return method.invoke(VoicePlaybackController, mediaPlayer) as Boolean
    }

    /** Delivers a seek completion through the controller's actual private settle path. */
    private fun settlePendingSeek(mediaPlayer: VoicePlaybackPlayer) {
        val method =
            VoicePlaybackController::class.java.getDeclaredMethod("settlePendingSeek", VoicePlaybackPlayer::class.java)
        method.isAccessible = true
        method.invoke(VoicePlaybackController, mediaPlayer)
    }

    /** Delivers a focus event through the controller's actual private callback. */
    private fun handleAudioFocusChange(change: Int) {
        val method = VoicePlaybackController::class.java.getDeclaredMethod("handleAudioFocusChange", Integer.TYPE)
        method.isAccessible = true
        method.invoke(VoicePlaybackController, change)
    }

    /**
     * Installs one retained codec and matching public state so controls can be tested without reopening a media
     * file.
     */
    private fun primeActivePlayer(mediaPlayer: TrackingVoicePlayer) {
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

    /** Reads private controller ownership for assertions without replacing its transition logic. */
    private fun controllerField(name: String): Any? {
        val field = VoicePlaybackController::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(VoicePlaybackController)
    }

    /** Reads the retained Android-focus owner to distinguish held, denied and abandoned requests. */
    private fun audioFocusOwnerField(name: String): Any? {
        val field = AudioFocusOwner::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(AudioFocusOwner)
    }

    /** Returns the installed focus listener so tests follow the real callback path. */
    private fun audioFocusListener() = audioFocusOwnerField("focusListener") as AudioManager.OnAudioFocusChangeListener

    /** Seeds only the test owner's focus fixture fields and leaves controller transition code intact. */
    private fun setAudioFocusOwnerField(
        name: String,
        value: Any?,
    ) {
        val field = AudioFocusOwner::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(AudioFocusOwner, value)
    }

    /** Publishes controlled session transitions through the controller flow observed by real shell controls. */
    @Suppress("UNCHECKED_CAST")
    private fun setPlaybackState(state: VoicePlaybackController.PlaybackState) {
        val field = VoicePlaybackController::class.java.getDeclaredField("_state")
        field.isAccessible = true
        val stateFlow =
            field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
        stateFlow.value = state
    }

    /** Sets a narrowly named controller fixture field before exercising the production boundary. */
    private fun setControllerField(
        name: String,
        value: Any?,
    ) {
        val field = VoicePlaybackController::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(VoicePlaybackController, value)
    }
}

/** A parser-free control seam; every failure-capable subclass preserves the production transition tests. */
private open class FakeVoicePlayer : VoicePlaybackPlayer {
    override val isPlaying: Boolean get() = false
    override val duration: Int get() = 0
    override val currentPosition: Int get() = 0

    override fun start() = Unit

    override fun pause() = Unit

    override fun stop() = Unit

    override fun release() = Unit

    override fun seekTo(positionMs: Int) = Unit

    override fun setSpeed(speed: Float) = Unit

    override fun setVolume(
        left: Float,
        right: Float,
    ) = Unit

    override fun setOnCompletionListener(listener: (VoicePlaybackPlayer) -> Unit) = Unit

    override fun setOnSeekCompleteListener(listener: (VoicePlaybackPlayer) -> Unit) = Unit

    override fun setOnErrorListener(listener: (VoicePlaybackPlayer, Int, Int) -> Boolean) = Unit
}

private class ThrowingStartVoicePlayer : FakeVoicePlayer() {
    var released = false
        private set

    override fun start(): Unit = throw IllegalStateException("start failed")

    override fun release() {
        released = true
    }
}

private class TrackingVoicePlayer(
    private val failPause: Boolean = false,
    private val failVolumeChange: Boolean = false,
    private val failVolumeRestore: Boolean = false,
    private val failPlayingQuery: Boolean = false,
    private val failSeek: Boolean = false,
) : FakeVoicePlayer() {
    var playing = true
    var released = false
    var leftVolume = 1f
    var rightVolume = 1f
    var startCount = 0
    var positionMs = 123
    var durationMs = 456
    var failDurationQuery = false
    var appliedSpeed = 1f

    override fun setSpeed(speed: Float) {
        appliedSpeed = speed
    }

    override val isPlaying: Boolean
        get() {
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

    override val currentPosition: Int get() = positionMs

    var seekedToMs: Int? = null

    /** Records the clamped target the controller hands to the codec, or rejects it like an invalid player. */
    override fun seekTo(msec: Int) {
        if (failSeek) throw IllegalStateException("seekTo failed")
        seekedToMs = msec
    }

    /** Models codecs that discover duration late or reject a metadata query without losing the player. */
    override val duration: Int
        get() {
            if (failDurationQuery) throw IllegalStateException("duration unavailable")
            return durationMs
        }

    override fun setVolume(
        leftVolume: Float,
        rightVolume: Float,
    ) {
        val restoringUnity = failVolumeRestore && leftVolume == 1f && rightVolume == 1f
        if (failVolumeChange || restoringUnity) {
            throw IllegalStateException("setVolume failed")
        }
        this.leftVolume = leftVolume
        this.rightVolume = rightVolume
    }

    override fun release() {
        released = true
    }
}
