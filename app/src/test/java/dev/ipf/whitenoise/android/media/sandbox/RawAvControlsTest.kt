package dev.ipf.whitenoise.android.media.sandbox

import android.app.Application
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.PlaybackParameters
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RawAvControlsTest {
    private val engines = mutableListOf<RawAvPlaybackEngine>()

    @After
    fun closeOwners() {
        engines.forEach { it.close() }
    }

    @Test
    fun stopThenPrepareKeepsPositionAndRestoresReadyWithoutReplayingTheParser() {
        val calls = AtomicInteger()
        val engine =
            newEngine {
                calls.incrementAndGet()
                null
            }
        seedPrepared(engine)
        val prior = field(engine, "prepared")
        engine.stop()
        assertEquals(RawAvPlaybackEngine.Status.Idle, engine.snapshot().status)
        assertEquals(2250L, engine.positionMs())

        val prepared = engine.prepare()

        assertNotSame(prior, prepared)
        assertNull(prepared.get(1, TimeUnit.SECONDS))
        assertEquals(RawAvPlaybackEngine.Status.Ready, engine.snapshot().status)
        assertEquals(2250L, engine.positionMs())
        assertFalse(engine.snapshot().playWhenReady)
        assertEquals(0, calls.get())
        quiesce(engine)
        assertTrue(engine.setPlaying(true))
        assertEquals(2250000L, engine.intent().positionUs)
        setField(engine, "status", RawAvPlaybackEngine.Status.Ready)
        engine.prepare()
        assertEquals(RawAvPlaybackEngine.Status.Ready, engine.snapshot().status)
        assertTrue(engine.snapshot().playWhenReady)
    }

    @Test
    fun stoppedVideoRetainsMatchingSeekPixelsWithoutStartingAnotherPosterRequest() {
        val calls = AtomicInteger()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val engine =
            RawAvPlaybackEngine(
                RuntimeEnvironment.getApplication(),
                Uri.parse("file:///fixture.mp4"),
                externalAudioFocus = true,
                listener =
                    object : RawAvPlaybackEngine.Listener {
                        override fun onChanged() = Unit
                    },
                prepareProbe = {
                    calls.incrementAndGet()
                    MediaProbe(2, 2, 4000, bitmap)
                },
            )
        engines += engine
        engine.prepare().get(2, TimeUnit.SECONDS)
        setField(engine, "pausedUs", 2250000L)
        setField(engine, "targetUs", 2250000L)
        engine.stop()
        engine.prepare().get(1, TimeUnit.SECONDS)
        assertSame(bitmap, field(engine, "lastBitmap"))
        assertFalse(bitmap.isRecycled)
        assertNull(field(engine, "pendingSeek"))
        assertEquals(1, calls.get())
        assertEquals(2250L, engine.positionMs())
    }

    @Test
    fun stoppedHeldInitialProbeCannotRestoreReadyOrPublishItsMetadata() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine =
            newEngine {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                MediaProbe(0, 0, 9999, null)
            }
        val old = engine.prepare()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            engine.stop()
            assertTrue(old.isCancelled)
        } finally {
            release.countDown()
        }
        awaitProbeIdle(engine)
        assertEquals(RawAvPlaybackEngine.Status.Idle, engine.snapshot().status)
        assertEquals(0L, engine.snapshot().durationMs)
        assertFalse(engine.snapshot().prepared)
    }

    @Test
    fun oldProbeCannotCompleteNewPreparationFutureWhileItsOwnSuccessorIsHeld() {
        val firstEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        val calls = AtomicInteger()
        val engine =
            newEngine {
                if (calls.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    check(firstRelease.await(3, TimeUnit.SECONDS))
                    MediaProbe(0, 0, 9999, null)
                } else {
                    secondEntered.countDown()
                    check(secondRelease.await(3, TimeUnit.SECONDS))
                    MediaProbe(0, 0, 4000, null)
                }
            }
        val first = engine.prepare()
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        try {
            engine.stop()
            val second = engine.prepare()
            assertNotSame(first, second)
            assertFalse(second.isDone)
            firstRelease.countDown()
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
            assertFalse(second.isDone)
            assertEquals(RawAvPlaybackEngine.Status.Buffering, engine.snapshot().status)
            assertEquals(0L, engine.snapshot().durationMs)
            secondRelease.countDown()
            second.get(2, TimeUnit.SECONDS)
            assertEquals(RawAvPlaybackEngine.Status.Ready, engine.snapshot().status)
            assertEquals(4000L, engine.snapshot().durationMs)
        } finally {
            firstRelease.countDown()
            secondRelease.countDown()
        }
    }

    @Test
    fun prepareAfterRendererFailureClearsOnlyThatErrorAndLaterCurrentFailureStillPublishes() {
        val engine = newEngine()
        seedPrepared(engine)
        setField(engine, "playIntent", true)
        val first = IOException("first decoder failure")
        engine.failed(0, 0, first)
        assertSame(first, engine.snapshot().error)
        val old = field(engine, "prepared")

        val fresh = engine.prepare()

        assertNotSame(old, fresh)
        fresh.get(1, TimeUnit.SECONDS)
        assertNull(engine.snapshot().error)
        assertEquals(RawAvPlaybackEngine.Status.Ready, engine.snapshot().status)
        engine.failed(0, 0, first)
        assertNull(engine.snapshot().error)
        setField(engine, "playIntent", true)
        val second = IOException("new decoder failure")
        engine.failed(engine.intent().activation, engine.intent().seek, second)
        assertSame(second, engine.snapshot().error)
    }

    @Test
    fun adapterSpeedRefusalDoesNotThrowToUiOrFalselyPublishTheRequestedRate() {
        for (rate in listOf(RawPcmCapacity.MIN_SPEED, RawPcmCapacity.MAX_SPEED)) {
            val player = IsolatedAttachmentPlayer(RuntimeEnvironment.getApplication(), Uri.parse("file:///fixture.mp4"))
            val engine = field(player, "engine") as RawAvPlaybackEngine
            engines += engine
            val sink = FakeSink(failSpeed = true)
            seedActiveOutput(engine, sink)
            try {
                player.prepare()
                shadowOf(Looper.getMainLooper()).idle()
                player.setPlaybackSpeed(rate)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1f, player.playbackParameters.speed, 0f)
                assertTrue(engine.snapshot().error is RawOutputFailure)
                assertNotNull(player.playerError)
                assertFalse(engine.snapshot().playWhenReady)
                assertTrue(sink.closed)
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun unsupportedPublicPlaybackParametersLeaveAcceptedRateAndHealthyOutputUnchanged() {
        val player = IsolatedAttachmentPlayer(RuntimeEnvironment.getApplication(), Uri.parse("file:///fixture.mp4"))
        val engine = field(player, "engine") as RawAvPlaybackEngine
        engines += engine
        val sink = FakeSink()
        seedActiveOutput(engine, sink)
        try {
            player.prepare()
            shadowOf(Looper.getMainLooper()).idle()
            player.setPlaybackSpeed(1.5f)
            shadowOf(Looper.getMainLooper()).idle()
            val unsupportedParameters =
                listOf(
                    PlaybackParameters(0.125f),
                    PlaybackParameters(3f),
                    PlaybackParameters(1f, 1.2f),
                )
            for (unsupported in unsupportedParameters) {
                player.playbackParameters = unsupported
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1.5f, player.playbackParameters.speed, 0f)
                assertEquals(1f, player.playbackParameters.pitch, 0f)
                assertEquals(1.5f, engine.snapshot().speed, 0f)
                assertNull(engine.snapshot().error)
                assertNull(player.playerError)
                assertFalse(sink.closed)
            }
        } finally {
            player.release()
        }
    }

    @Test
    fun defaultVideoQuarterAndMaximumChoicesAreAcceptedAndRejectedChangesPreserveThem() {
        val player = IsolatedAttachmentPlayer(RuntimeEnvironment.getApplication(), Uri.parse("file:///fixture.mp4"))
        val engine = field(player, "engine") as RawAvPlaybackEngine
        engines += engine
        val sink = FakeSink()
        seedActiveOutput(engine, sink)
        try {
            player.prepare()
            shadowOf(Looper.getMainLooper()).idle()
            val unsupported =
                listOf(
                    PlaybackParameters(0.125f),
                    PlaybackParameters(3f),
                    PlaybackParameters(1f, 1.2f),
                )
            for (rate in listOf(RawPcmCapacity.MIN_SPEED, RawPcmCapacity.MAX_SPEED)) {
                player.setPlaybackSpeed(rate)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(rate, player.playbackParameters.speed, 0f)
                assertEquals(rate, engine.snapshot().speed, 0f)
                assertEquals(rate, sink.appliedSpeed, 0f)
                for (rejected in unsupported) {
                    player.playbackParameters = rejected
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(rate, player.playbackParameters.speed, 0f)
                    assertEquals(1f, player.playbackParameters.pitch, 0f)
                    assertEquals(rate, engine.snapshot().speed, 0f)
                    assertEquals(rate, sink.appliedSpeed, 0f)
                    assertNull(engine.snapshot().error)
                    assertNull(player.playerError)
                    assertFalse(sink.closed)
                }
            }
        } finally {
            player.release()
        }
    }

    @Test
    fun volumeAndFocusDuckRefusalsRetireOnlyTheExactOutputWithoutCommittingRejectedGain() {
        for (change in listOf(null, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, AudioManager.AUDIOFOCUS_GAIN)) {
            val engine = newEngine()
            val sink = FakeSink(failVolume = true)
            seedActiveOutput(engine, sink)
            if (change != null) {
                if (change == AudioManager.AUDIOFOCUS_GAIN) setField(engine, "ducked", true)
                val method = engine.javaClass.getDeclaredMethod("focusChanged", Int::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(engine, change)
            } else {
                assertFalse(engine.setVolume(0.5f))
            }
            assertEquals(1f, engine.snapshot().volume, 0f)
            assertTrue(engine.snapshot().error is RawOutputFailure)
            assertFalse(engine.snapshot().playWhenReady)
            assertTrue(sink.closed)
        }
    }

    @Test
    fun validPauseRefusalCannotEscapeUiAndStillRetiresTheOutput() {
        val engine = newEngine()
        val sink = FakeSink(failPause = true)
        seedActiveOutput(engine, sink)
        assertTrue(engine.setPlaying(false))
        assertTrue(engine.snapshot().error is RawOutputFailure)
        assertEquals(RawAvPlaybackEngine.Status.Idle, engine.snapshot().status)
        assertEquals(1500L, engine.snapshot().positionMs)
        assertFalse(engine.snapshot().playWhenReady)
        assertTrue(sink.closed)
    }

    @Test
    fun staleRendererFailureCannotReplaceTheCurrentGenerationState() {
        val engine = newEngine()
        seedPrepared(engine)
        setField(engine, "activation", 2L)
        setField(engine, "playIntent", true)
        engine.failed(1, 0, RawOutputFailure("retired device output"))
        assertNull(engine.snapshot().error)
        assertTrue(engine.snapshot().playWhenReady)
    }

    @Test
    fun heldSeekAcknowledgementUsesLatestAcceptedSpeedAndGainForItsNewSink() {
        val owner = FakeOwner()
        val sink = FakeSink()
        val renderer = renderer(owner) { _, _, _ -> sink }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val pending =
            executor.submit {
                renderer.applySeek(owner.intent()) {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(renderer.speed(2f))
            assertNull(renderer.volume(0.4f))
            release.countDown()
            pending.get(2, TimeUnit.SECONDS)
            renderer.createPcm(AvStreamFrame.Audio(ByteArray(192), 48000, 1, 1500000), 1500000, 7)
            assertEquals(2f, sink.appliedSpeed, 0f)
            assertEquals(0.4f, sink.appliedVolume, 0f)
            assertEquals(2f, field(renderer, "clockSpeed") as Float, 0f)
            assertTrue(sink.playing)
        } finally {
            release.countDown()
            executor.shutdownNow()
            renderer.pause()
        }
    }

    @Test
    fun refusedNewSinkClosesBeforeAnyAdoptionForSpeedVolumeOrPlayFailure() {
        for (kind in listOf("speed", "volume", "play")) {
            val sink = FakeSink(failSpeed = kind == "speed", failVolume = kind == "volume", failPlay = kind == "play")
            val renderer = renderer(FakeOwner()) { _, _, _ -> sink }
            val failure =
                runCatching {
                    renderer.createPcm(AvStreamFrame.Audio(ByteArray(192), 48000, 1, 0), 0, 7)
                }.exceptionOrNull()
            assertTrue(failure is RawOutputFailure)
            assertTrue(sink.closed)
            assertNull(field(renderer, "pcm"))
        }
    }

    private fun newEngine(probe: () -> MediaProbe? = { null }): RawAvPlaybackEngine =
        RawAvPlaybackEngine(
            RuntimeEnvironment.getApplication(),
            Uri.parse("file:///fixture.mp4"),
            wantVideo = false,
            wantAudio = true,
            externalAudioFocus = true,
            listener =
                object : RawAvPlaybackEngine.Listener {
                    override fun onChanged() = Unit
                },
            prepareProbe = probe,
        ).also { engines += it }

    @Suppress("UNCHECKED_CAST")
    private fun seedPrepared(engine: RawAvPlaybackEngine) {
        setField(engine, "headerReady", true)
        setField(engine, "status", RawAvPlaybackEngine.Status.Ready)
        setField(engine, "pausedUs", 2250000L)
        setField(engine, "targetUs", 2250000L)
        (field(engine, "prepared") as SettableFuture<Void?>).set(null)
    }

    private fun seedActiveOutput(
        engine: RawAvPlaybackEngine,
        sink: FakeSink,
    ) {
        quiesce(engine)
        seedPrepared(engine)
        setField(engine, "playIntent", true)
        val renderer = field(engine, "renderer") as RawAvRenderSession
        setField(renderer, "activeRun", 0L)
        setField(renderer, "appliedSeek", 0L)
        setField(renderer, "sourceHasAudio", true)
        setField(renderer, "pcm", sink)
    }

    private fun quiesce(engine: RawAvPlaybackEngine) =
        runBlocking {
            withTimeout(2000) {
                val job = checkNotNull((field(engine, "scope") as CoroutineScope).coroutineContext[Job])
                job.cancelAndJoin()
            }
        }

    private fun awaitProbeIdle(engine: RawAvPlaybackEngine) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (synchronized(checkNotNull(field(engine, "lock"))) { field(engine, "prepareJob") == null }) return
            Thread.sleep(5)
        }
        throw AssertionError("Held probe did not release its generation")
    }

    private fun field(
        target: Any,
        name: String,
    ): Any? =
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(target)
        }

    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.set(target, value)
        }
    }

    private fun renderer(
        owner: FakeOwner,
        factory: (Int, Int, AudioAttributes) -> RawPcmSink,
    ): RawAvRenderSession {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("file:///fixture.mp4")
        return RawAvRenderSession(context, uri, false, true, owner, factory)
    }

    private class FakeOwner : RawAvRenderOwner {
        override fun intent(): RawAvPlaybackIntent {
            val attributes = AudioAttributes.Builder().build()
            return RawAvPlaybackIntent(true, true, 7, 1, 1500000, 1f, 1f, attributes)
        }

        override fun awaitPrepared() = Unit

        override fun opened(
            info: AvStreamInfo,
            activation: Long,
        ) = Unit

        override fun seeked(
            activation: Long,
            seek: Long,
        ) = Unit

        override fun frame(
            bitmap: Bitmap,
            rotation: Int,
            activation: Long,
            seek: Long,
        ) = false

        override fun ended(
            activation: Long,
            seek: Long,
            endUs: Long,
        ) = Unit

        override fun failed(
            activation: Long,
            seek: Long?,
            failure: IOException,
        ) = Unit
    }

    private class FakeSink(
        private val failSpeed: Boolean = false,
        private val failVolume: Boolean = false,
        private val failPause: Boolean = false,
        private val failPlay: Boolean = false,
    ) : RawPcmSink {
        override val sampleRate = 48000
        override val channels = 1
        var closed = false
        var playing = false
        var appliedSpeed = 1f
        var appliedVolume = 1f

        override fun write(
            bytes: ByteArray,
            offset: Int,
        ): Int = bytes.size - offset

        override fun play() {
            if (failPlay) error("device play refusal")
            playing = true
        }

        override fun pause() {
            if (failPause) error("device pause refusal")
            playing = false
        }

        override fun speed(value: Float) {
            if (failSpeed) error("device speed refusal")
            appliedSpeed = value
        }

        override fun volume(value: Float) {
            if (failVolume) error("device volume refusal")
            appliedVolume = value
        }

        override fun drained() = true

        override fun writtenPositionUs() = 1500000L

        override fun positionUs() = 1500000L

        override fun close() {
            closed = true
            playing = false
        }
    }
}
