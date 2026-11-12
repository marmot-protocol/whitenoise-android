package dev.ipf.whitenoise.android.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Ownership of a prepared voice player when its caller is cancelled before adoption (#370). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoicePlaybackControllerCancellationTest {
    /** Restores the shared controller so later fixtures start from an idle player and default factory. */
    @After
    fun tearDown() {
        // A cancelled request must leave the process-wide serializer free for the next play.
        assertFalse("playback serializer still locked", serializerLocked())
        VoicePlaybackController.stop()
        VoicePlaybackController.noisyRoutes = NoisyAudioRouteRegistrar.None
        setControllerField("playerFactory", RawVoicePlaybackPlayer.Factory)
    }

    /** A player prepared just before cancellation is released even though withContext discards it (#370). */
    @Test
    fun cancellationDuringPreparedHandoffReleasesTheUndeliveredPlayer() {
        val routes = attachWithNoisyRoutes()
        val mediaPlayer = PreparedProbePlayer()
        setControllerField("playerFactory", VoicePlaybackPlayer.Factory { _, _ -> mediaPlayer })
        val caller = QueuedDispatcher()

        val job = CoroutineScope(caller).launch { VoicePlaybackController.play("new-source", File("voice.amr")) }
        caller.runNext()
        // The IO block has returned the player and its resume is queued on the held caller.
        caller.awaitQueued()
        job.cancel()
        caller.drain()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertTrue(mediaPlayer.released)
        assertEquals(0, mediaPlayer.startCount)
        assertNull(controllerField("player"))
        assertEquals(0, routes.listeners)
        assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
    }

    /** Cancellation while the factory is still preparing releases the player once it is returned. */
    @Test
    fun cancellationWhilePreparingReleasesThePlayerWhenPreparationEnds() {
        val routes = attachWithNoisyRoutes()
        val mediaPlayer = PreparedProbePlayer()
        val preparing = CountDownLatch(1)
        val finishPrepare = CountDownLatch(1)
        setControllerField(
            "playerFactory",
            VoicePlaybackPlayer.Factory { _, _ ->
                preparing.countDown()
                finishPrepare.await()
                mediaPlayer
            },
        )
        val caller = QueuedDispatcher()

        val job = CoroutineScope(caller).launch { VoicePlaybackController.play("new-source", File("voice.amr")) }
        caller.runNext()
        assertTrue(preparing.await(5, TimeUnit.SECONDS))
        job.cancel()
        finishPrepare.countDown()
        caller.awaitQueued()
        caller.drain()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertTrue(mediaPlayer.released)
        assertEquals(0, mediaPlayer.startCount)
        assertEquals(0, routes.listeners)
        assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
    }

    /** A cancelled request's late cleanup leaves another session's displayed state alone. */
    @Test
    fun cancelledPrepareLeavesAReplacedSessionStateAlone() {
        attachWithNoisyRoutes()
        val mediaPlayer = PreparedProbePlayer()
        val replacement = VoicePlaybackController.PlaybackState(key = "other", sessionId = Long.MAX_VALUE)
        setControllerField(
            "playerFactory",
            VoicePlaybackPlayer.Factory { _, _ ->
                setPlaybackState(replacement)
                mediaPlayer
            },
        )
        val caller = QueuedDispatcher()

        val job = CoroutineScope(caller).launch { VoicePlaybackController.play("new-source", File("voice.amr")) }
        caller.runNext()
        caller.awaitQueued()
        job.cancel()
        caller.drain()

        assertTrue(job.isCompleted)
        assertTrue(mediaPlayer.released)
        assertSame(replacement, VoicePlaybackController.state.value)
    }

    /** A factory failure still clears the loading state and listener without a player to release. */
    @Test
    fun prepareFailureClearsLoadingStateAndListener() {
        val routes = attachWithNoisyRoutes()
        setControllerField("playerFactory", VoicePlaybackPlayer.Factory { _, _ -> throw IOException("bad clip") })

        val result = runBlocking { VoicePlaybackController.play("new-source", File("voice.amr")) }

        assertEquals(VoicePlaybackController.PlaybackStartResult.PrepareFailed, result)
        assertEquals(0, routes.listeners)
        assertEquals(VoicePlaybackController.PlaybackState(), VoicePlaybackController.state.value)
    }
}

/** Records release and start calls on a prepared player that never begins playing. */
private class PreparedProbePlayer : VoicePlaybackPlayer {
    var released = false
        private set
    var startCount = 0
        private set

    override val isPlaying: Boolean get() = false
    override val duration: Int get() = 0
    override val currentPosition: Int get() = 0

    override fun start() {
        startCount += 1
    }

    override fun pause() = Unit

    override fun stop() = Unit

    override fun release() {
        released = true
    }

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

/** Attaches the controller with a test headphone route in place of the platform receiver. */
private fun attachWithNoisyRoutes(): FakeNoisyRoutes {
    VoicePlaybackController.attach(RuntimeEnvironment.getApplication())
    return FakeNoisyRoutes().also { VoicePlaybackController.noisyRoutes = it }
}

/** Reads private controller ownership for assertions. */
private fun controllerField(name: String): Any? {
    val field = VoicePlaybackController::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.get(VoicePlaybackController)
}

/** Sets a controller fixture field before exercising the production boundary. */
private fun setControllerField(
    name: String,
    value: Any?,
) {
    val field = VoicePlaybackController::class.java.getDeclaredField(name)
    field.isAccessible = true
    field.set(VoicePlaybackController, value)
}

/** Reports whether the controller's play serializer is still held. */
private fun serializerLocked(): Boolean {
    val serializer = controllerField("playSerializer")!!
    val mutex = serializer.javaClass.getDeclaredField("mutex").apply { isAccessible = true }.get(serializer)
    return (mutex as Mutex).isLocked
}

/** Publishes a replacement session through the controller state flow. */
@Suppress("UNCHECKED_CAST")
private fun setPlaybackState(state: VoicePlaybackController.PlaybackState) {
    val field = VoicePlaybackController::class.java.getDeclaredField("_state")
    field.isAccessible = true
    (field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>).value = state
}

/** Holds caller resumptions until the test drains them, exposing the withContext handoff window. */
private class QueuedDispatcher : CoroutineDispatcher() {
    private val queue = LinkedBlockingDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queue.put(block)
    }

    /** Waits for the IO handoff; generous because loaded CI shards can stall the IO pool for seconds. */
    fun awaitQueued() {
        val next =
            checkNotNull(queue.poll(30, TimeUnit.SECONDS)) {
                "caller resume was never dispatched (serializer locked: ${serializerLocked()})"
            }
        queue.putFirst(next)
    }

    /** Runs exactly one queued task, so a resume that lands meanwhile stays held. */
    fun runNext() = checkNotNull(queue.poll()) { "nothing queued" }.run()

    fun drain() {
        while (true) (queue.poll() ?: return).run()
    }
}
