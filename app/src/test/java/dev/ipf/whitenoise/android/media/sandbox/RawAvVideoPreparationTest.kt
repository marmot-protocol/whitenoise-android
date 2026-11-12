package dev.ipf.whitenoise.android.media.sandbox

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RawAvVideoPreparationTest {
    /** Video playing intent admits raw preparation without opening an optional metadata worker. */
    @Test
    fun videoPlayingPreparationDoesNotRunOptionalProbe() {
        val probes = AtomicInteger()
        val engine =
            engine {
                probes.incrementAndGet()
                MediaProbe(20, 10, 1000, null)
            }
        try {
            setField(engine, "playIntent", true)
            val prepared = engine.prepare()
            prepared.get(1, TimeUnit.SECONDS)
            assertTrue(engine.snapshot().prepared)
            assertEquals(RawAvPlaybackEngine.Status.Buffering, engine.snapshot().status)
            assertEquals(0, probes.get())
            assertSame(prepared, engine.prepare())
        } finally {
            engine.close()
        }
    }

    /** A held paused probe keeps ownership after play admission; its late metadata cannot replace raw OPEN. */
    @Test
    fun heldPausedProbeCannotOverwriteRawOpenOrLoseItsOwnershipOnPromotion() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latePoster = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        val engine =
            engine {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                MediaProbe(20, 10, 1000, latePoster)
            }
        try {
            val paused = engine.prepare()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(paused.isDone)
            val job = field(engine, "prepareJob") as Job
            // This is the existing owner-generation seam: no renderer or real isolated worker is opened here.
            setField(engine, "playIntent", true)
            assertSame(paused, engine.prepare())
            paused.get(1, TimeUnit.SECONDS)
            assertSame(job, field(engine, "prepareJob"))
            val activation = engine.intent().activation
            val pendingSeek = SettableFuture.create<Void?>()
            setField(engine, "targetUs", 2_000_000L)
            setField(engine, "seekRevision", 7L)
            setField(engine, "pendingSeek", pendingSeek)
            engine.opened(AvStreamInfo(640, 360, 9_000_000L, 0, 48000, 2), activation)
            assertEquals(2_000_000L, engine.intent().positionUs)
            assertEquals(7L, engine.intent().seek)
            assertSame(pendingSeek, field(engine, "pendingSeek"))
            assertFalse(pendingSeek.isDone)
            // Stop playback before the held producer returns, so the test never opens a real raw stream.
            engine.setPlaying(false)
            release.countDown()
            awaitProbeReturn(engine, job)
            assertTrue(latePoster.isRecycled)
            assertEquals(640, engine.snapshot().videoWidth)
            assertEquals(360, engine.snapshot().videoHeight)
            assertEquals(9000L, engine.snapshot().durationMs)
            assertEquals(RawAvPlaybackEngine.Status.Ready, engine.snapshot().status)
        } finally {
            release.countDown()
            engine.close()
            if (!latePoster.isRecycled) latePoster.recycle()
        }
    }

    /** Paused-only preparation still waits for and adopts its optional metadata. */
    @Test
    fun pausedVideoRetainsOptionalPreparation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine =
            engine {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                MediaProbe(20, 10, 1000, null)
            }
        try {
            val prepared = engine.prepare()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(prepared.isDone)
            release.countDown()
            prepared.get(2, TimeUnit.SECONDS)
            assertEquals(1000L, engine.snapshot().durationMs)
            assertEquals(RawAvPlaybackEngine.Status.Ready, engine.snapshot().status)
        } finally {
            release.countDown()
            engine.close()
        }
    }

    /** Stop and account retirement reject a raw header captured before revocation. */
    @Test
    fun stoppedOrRetiredVideoCannotAdoptCapturedRawHeader() {
        for (retire in listOf(false, true)) {
            val engine = engine { error("Playing video must not probe") }
            try {
                setField(engine, "playIntent", true)
                engine.prepare().get(1, TimeUnit.SECONDS)
                val activation = engine.intent().activation
                if (retire) RawAvPlaybackEngine.retireAll() else engine.stop()
                engine.opened(AvStreamInfo(640, 360, 9_000_000L, 0, 48000, 2), activation)
                assertEquals(0, engine.snapshot().videoWidth)
                assertEquals(0L, engine.snapshot().durationMs)
                assertFalse(engine.intent().playing)
            } finally {
                engine.close()
            }
        }
    }

    private fun engine(probe: () -> MediaProbe?): RawAvPlaybackEngine =
        RawAvPlaybackEngine(
            RuntimeEnvironment.getApplication(),
            Uri.parse("file:///fixture.mp4"),
            externalAudioFocus = true,
            listener =
                object : RawAvPlaybackEngine.Listener {
                    override fun onChanged() = Unit
                },
            prepareProbe = probe,
        )

    private fun awaitProbeReturn(
        engine: RawAvPlaybackEngine,
        job: Job,
    ) = runBlocking {
        withTimeout(2_000L) { job.join() }
        assertEquals(null, field(engine, "prepareJob"))
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
}
