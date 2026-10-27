package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Real production-viewer playback on a disposable emulator; no account, relay or personal data is used. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class AttachmentVideoPauseAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var fixture: EdgeClipViewerFixture
    private lateinit var activity: ComponentActivity
    private var materializationGate: CompletableDeferred<Unit>? = null

    @Volatile
    private var materializationEntered = false
    private var everRequestedPlayback = false

    /** Disables the fixture activity's lock-screen exemption so screen-off delivers the real STOP event. */
    @Before
    fun setUp() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "This route-injection test requires a disposable emulator"
        }
        activity = composeRule.activity
        onMain {
            activity.setShowWhenLocked(false)
            activity.setTurnScreenOn(false)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        fixture =
            EdgeClipViewerFixture(
                composeRule,
                context,
                beforeMaterialize = {
                    materializationEntered = true
                    materializationGate?.await()
                },
                onPlayerCreated = { player ->
                    player.addListener(
                        object : Player.Listener {
                            /** Records even brief autoplay before the final paused assertion. */
                            override fun onPlayWhenReadyChanged(
                                playWhenReady: Boolean,
                                reason: Int,
                            ) {
                                if (playWhenReady) everRequestedPlayback = true
                            }
                        },
                    )
                },
            )
        composeRule.setContent { WhiteNoiseTheme(darkTheme = true) { fixture.Content() } }
    }

    /** Restores the emulator's foreground and activity flags before releasing only private fixture files. */
    @After
    fun tearDown() {
        if (!::activity.isInitialized) return
        try {
            returnToViewer()
            if (::fixture.isInitialized) fixture.release()
        } finally {
            onMain {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    /** Home stops active playback; returning keeps both the stop and a pre-existing user pause. */
    @Test
    fun homeReturnRetainsPositionAndNeverResumesPlayingOrUserPausedVideo() {
        val player = openPlayingVideo()
        assertTrue(device.pressHome())
        awaitStopped(player)
        val stoppedAt = onMain { player.currentPosition }
        returnToViewer()
        assertPausedAt(player, stoppedAt)

        onMain { player.seekTo(USER_SEEK_MS) }
        assertTrue(device.pressHome())
        awaitStopped(player)
        returnToViewer()
        assertPausedAt(player, USER_SEEK_MS)
        onMain { player.play() }
        await("explicit user play") { onMain { player.isPlaying } }
    }

    /** Screen off reaches STOP, and unlock returns to the same silent player and position. */
    @Test
    fun screenLockAndUnlockKeepTheMountedVideoPaused() {
        val player = openPlayingVideo()
        device.sleep()
        awaitStopped(player)
        val stoppedAt = onMain { player.currentPosition }
        returnToViewer()
        assertPausedAt(player, stoppedAt)
    }

    /** Delivers Android's protected disconnect/reconnect broadcasts to Media3's real registered receiver. */
    @Test
    fun noisyRoutePausesThroughMedia3AndReconnectDoesNotResume() {
        val player = openPlayingVideo()
        var pauseReason: Int? = null
        onMain {
            player.addListener(
                object : Player.Listener {
                    /** Distinguishes Media3's noisy receiver from lifecycle or focus loss. */
                    override fun onPlayWhenReadyChanged(
                        playWhenReady: Boolean,
                        reason: Int,
                    ) {
                        if (!playWhenReady) pauseReason = reason
                    }
                },
            )
        }
        systemBroadcast(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        await("Media3 noisy-route pause") {
            onMain { !player.playWhenReady && pauseReason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY }
        }
        val pausedAt = onMain { player.currentPosition }
        systemBroadcast(Intent.ACTION_HEADSET_PLUG, "--ei state 1 --ei microphone 1 --es name fixture")
        assertPausedAt(player, pausedAt)
        onMain { player.play() }
        await("explicit play after reconnect") { onMain { player.isPlaying } }
    }

    /** A new materialized file cannot erase the pause imposed by Media3's real noisy-route receiver. */
    @Test
    fun noisyPauseSurvivesMaterializedFileReplacement() {
        val player = openPlayingVideo()
        systemBroadcast(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        await("noisy pause before file replacement") { onMain { !player.playWhenReady && !player.isPlaying } }
        systemBroadcast(Intent.ACTION_HEADSET_PLUG, "--ei state 1 --ei microphone 1 --es name fixture")
        assertFileReplacementStaysPaused(player)
    }

    /** Replacing the file of a manually paused attachment also requires another explicit Play action. */
    @Test
    fun userPauseSurvivesMaterializedFileReplacement() {
        val player = openPlayingVideo()
        onMain { player.pause() }
        assertFileReplacementStaysPaused(player)
    }

    /** Requires an actually replaced, decoded player and rejects even a brief autoplay request. */
    private fun assertFileReplacementStaysPaused(previous: Player) {
        onMain { everRequestedPlayback = false }
        fixture.replaceCurrentPlaybackFile()
        fixture.awaitCondition("the refreshed file to replace the player") { fixture.currentPlayer() !== previous }
        fixture.awaitFirstFrame()
        val replacement = fixture.currentPlayer()
        assertNotSame(previous, replacement)
        assertFalse("file replacement briefly restarted playback", onMain { everRequestedPlayback })
        assertPausedAt(replacement, 0L)
        onMain { replacement.play() }
        await("explicit play after file replacement") { onMain { replacement.isPlaying } }
        fixture.replaceCurrentPlaybackFile()
        fixture.awaitCondition("explicit play intent to survive another file replacement") {
            val resumed = fixture.currentPlayer()
            resumed !== replacement && onMain { resumed.isPlaying }
        }
    }

    /** Rotation preserves pause and position; paging stops the previous decoder and disposal stops the current one. */
    @Test
    fun pausedRotationPagingAndDisposalKeepOnePlaybackOwner() {
        val player = openPlayingVideo()
        onMain {
            player.pause()
            player.seekTo(USER_SEEK_MS)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        await("landscape configuration") {
            onMain { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        }
        assertPausedAt(player, USER_SEEK_MS)
        onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("portrait configuration") {
            onMain { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        }
        onMain { player.play() }
        await("play before paging") { onMain { player.isPlaying } }
        fixture.swipePicture(fixture.geometry())
        fixture.awaitAttachment(EdgeClip.Landscape.ordinal)
        val next = fixture.currentPlayer()
        await("exclusive next-page playback") {
            onMain { next.isPlaying && !player.playWhenReady && player.playbackState == Player.STATE_IDLE }
        }
        fixture.close()
        assertFalse("disposed page still plays", onMain { next.isPlaying })
    }

    /** A file published behind Home remains paused even if its player is not composed until return. */
    @Test
    fun materializationCompletingBehindHomeDoesNotAutoplayOnReturn() {
        assertPendingOpenStaysPaused(completeWhileStopped = true)
    }

    /** Returning before file resolution finishes must not erase the earlier STOP admission decision. */
    @Test
    fun materializationCompletingAfterReturnRetainsTheEarlierStop() {
        assertPendingOpenStaysPaused(completeWhileStopped = false)
    }

    /** Holds the real resolver across STOP and proves no player ever requests playback until explicitly played. */
    private fun assertPendingOpenStaysPaused(completeWhileStopped: Boolean) {
        val gate = CompletableDeferred<Unit>()
        materializationGate = gate
        fixture.beginOpen(EdgeClipScenario(listOf(EdgeClip.Portrait)))
        fixture.awaitCondition("pending materialization to enter") { materializationEntered }
        assertTrue(device.pressHome())
        await("STOP before file publication") {
            onMain { activity.lifecycle.currentState == Lifecycle.State.CREATED }
        }
        if (completeWhileStopped) {
            gate.complete(Unit)
            await("file publication behind Home") { fixture.materializationComplete }
        }
        returnToViewer()
        if (!completeWhileStopped) gate.complete(Unit)
        fixture.awaitFirstFrame()
        val player = fixture.currentPlayer()
        assertFalse("pending resolution briefly requested playback after STOP", onMain { everRequestedPlayback })
        assertPausedAt(player, 0L)
        onMain { player.play() }
        await("explicit play after delayed materialization") { onMain { player.isPlaying } }
    }

    /** Opens the production pager and starts its local decoded clip with enough duration for lifecycle probes. */
    private fun openPlayingVideo(): Player {
        fixture.open(EdgeClipScenario(listOf(EdgeClip.Portrait, EdgeClip.Landscape)))
        val player = fixture.currentPlayer()
        onMain {
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.seekTo(USER_SEEK_MS)
            player.play()
        }
        await("real decoded playback") { onMain { player.isPlaying && player.currentPosition > USER_SEEK_MS } }
        return player
    }

    /** Waits without Compose idling, which cannot complete a frame while the activity is stopped. */
    private fun awaitStopped(player: Player) {
        await("activity STOP and player pause") {
            onMain {
                activity.lifecycle.currentState == Lifecycle.State.CREATED && !player.playWhenReady && !player.isPlaying
            }
        }
    }

    /** Wakes only the disposable emulator and brings the existing activity/task back without recreation. */
    private fun returnToViewer() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        context.startActivity(
            Intent(context, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        await("retained activity to resume") { onMain { activity.lifecycle.currentState == Lifecycle.State.RESUMED } }
        composeRule.waitForIdle()
    }

    /** Proves the same page remains paused across several frames, with its seek position intact. */
    private fun assertPausedAt(
        player: Player,
        positionMs: Long,
    ) {
        assertSame("the retained viewer replaced its player", player, fixture.currentPlayer())
        repeat(PAUSED_OBSERVATIONS) {
            onMain {
                assertFalse("foreground or route reconnect restarted playback", player.playWhenReady)
                assertFalse(player.isPlaying)
                assertTrue("paused position changed", abs(player.currentPosition - positionMs) <= POSITION_TOLERANCE_MS)
            }
            SystemClock.sleep(POLL_MS)
        }
        assertEquals(EdgeClip.Portrait.ordinal, fixture.currentAttachment)
    }

    /** Root is needed only to inject protected platform route broadcasts; no receiver or native policy is bypassed. */
    private fun systemBroadcast(
        action: String,
        extras: String = "",
    ) {
        val result = device.executeShellCommand("su 0 am broadcast -a $action -p ${context.packageName} $extras")
        assertTrue(
            "protected route broadcast failed: $result",
            "Broadcast completed" in result && "Permission Denial" !in result,
        )
    }

    /** Bounded polling works across Home and screen-off, where Compose cannot drive its test clock. */
    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $description" }
            SystemClock.sleep(POLL_MS)
        }
    }
}

private const val USER_SEEK_MS = 2_000L
private const val POSITION_TOLERANCE_MS = 300L
private const val TIMEOUT_MS = 15_000L
private const val POLL_MS = 50L
private const val PAUSED_OBSERVATIONS = 10
