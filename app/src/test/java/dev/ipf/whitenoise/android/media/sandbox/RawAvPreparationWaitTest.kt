package dev.ipf.whitenoise.android.media.sandbox

import android.app.Application
import android.content.Context
import android.net.Uri
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RawAvPreparationWaitTest {
    @Test
    fun selectedRenderActivationHeldBeforeWaitCannotPrepareOrOpenAfterStop() {
        val context = RuntimeEnvironment.getApplication<Application>()
        val probes = AtomicInteger()
        val engine = engine(context, probes)
        val prior = field(engine, "prepared")
        setField(engine, "playIntent", true)
        val activation = engine.intent().activation
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val owner =
            object : RawAvRenderOwner by engine {
                override fun awaitPrepared() {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    engine.awaitPrepared()
                }

                override fun opened(
                    info: AvStreamInfo,
                    activation: Long,
                ) {
                    opens.incrementAndGet()
                    engine.opened(info, activation)
                }
            }
        val renderer = RawAvRenderSession(context, Uri.parse("file:///fixture.mp4"), false, true, owner)
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit { runBlocking { renderer.run(activation) } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            engine.stop()
            release.countDown()
            task.get(2, TimeUnit.SECONDS)
            assertEquals(RawAvPlaybackEngine.Status.Idle, engine.snapshot().status)
            assertSame(prior, field(engine, "prepared"))
            assertFalse(engine.snapshot().prepared)
            assertEquals(0, probes.get())
            assertEquals(0, opens.get())
            assertEquals(-1L, renderer.activeRun)
        } finally {
            release.countDown()
            executor.shutdownNow()
            renderer.pause()
            engine.close()
        }
    }

    @Test
    fun queuedPausedSeekHeldBeforeWaitCannotRestartStoppedPreparationAndProducerStillAcceptsLaterTargets() {
        val context = RuntimeEnvironment.getApplication<Application>()
        val probes = AtomicInteger()
        val engine = engine(context, probes)
        val prior = field(engine, "prepared")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val results = AtomicInteger()
        val owner =
            object : RawAvPausedPosterOwner by engine {
                override fun awaitPrepared() {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    engine.awaitPrepared()
                }

                // Model a bounded optional poster refusal without opening any source in this control test.
                override fun posterCurrent(target: RawAvPosterTarget): Boolean = false

                override fun posterResult(
                    request: RawAvPausedSeekRequest,
                    probe: MediaProbe?,
                ): Boolean {
                    results.incrementAndGet()
                    return engine.posterResult(request, probe).also { returned.countDown() }
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val producer = RawAvPausedPoster(context, Uri.parse("file:///fixture.mp4"), scope, owner)
        val first = request(engine, 1, 1500)
        producer.submit(first)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            engine.stop()
            assertTrue(first.completion.isCancelled)
            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals(RawAvPlaybackEngine.Status.Idle, engine.snapshot().status)
            assertSame(prior, field(engine, "prepared"))
            assertEquals(0, probes.get())

            // Only this new admitted control starts preparation; cancellation did not kill the paused producer.
            engine.prepare().get(2, TimeUnit.SECONDS)
            val second = request(engine, 2, 2000)
            producer.submit(second)
            second.completion.get(2, TimeUnit.SECONDS)
            assertEquals(2, results.get())
            assertEquals(1, probes.get())
            assertEquals(RawAvPlaybackEngine.Status.Ready, engine.snapshot().status)
        } finally {
            release.countDown()
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun seekBeforeFirstPrepareWaitsForTheSamePromiseUntilAnExplicitControlAdmitsIt() {
        val context = RuntimeEnvironment.getApplication<Application>()
        val probes = AtomicInteger()
        val engine = engine(context, probes)
        val initial = field(engine, "prepared")
        val entered = CountDownLatch(1)
        val owner =
            object : RawAvPausedPosterOwner by engine {
                override fun awaitPrepared() {
                    entered.countDown()
                    engine.awaitPrepared()
                }

                override fun posterCurrent(target: RawAvPosterTarget): Boolean = false
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val producer = RawAvPausedPoster(context, Uri.parse("file:///fixture.mp4"), scope, owner)
        val target = request(engine, 1, 1500)
        producer.submit(target)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(0, probes.get())
            assertFalse(target.completion.isDone)
            val admitted = engine.prepare()
            assertSame(initial, admitted)
            admitted.get(2, TimeUnit.SECONDS)
            target.completion.get(2, TimeUnit.SECONDS)
            assertEquals(1, probes.get())
            assertEquals(RawAvPlaybackEngine.Status.Ready, engine.snapshot().status)
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    private fun engine(
        context: Context,
        probes: AtomicInteger,
    ): RawAvPlaybackEngine =
        RawAvPlaybackEngine(
            context,
            Uri.parse("file:///fixture.mp4"),
            wantVideo = false,
            externalAudioFocus = true,
            listener =
                object : RawAvPlaybackEngine.Listener {
                    override fun onChanged() = Unit
                },
            prepareProbe = {
                probes.incrementAndGet()
                MediaProbe(0, 0, 4000, null)
            },
        )

    private fun request(
        engine: RawAvPlaybackEngine,
        seek: Long,
        positionMs: Long,
    ): RawAvPausedSeekRequest {
        val completion = SettableFuture.create<Void>()
        setField(engine, "seekRevision", seek)
        setField(engine, "targetUs", positionMs * 1000)
        setField(engine, "pausedUs", positionMs * 1000)
        setField(engine, "pendingSeek", completion)
        return RawAvPausedSeekRequest(
            RawAvPosterTarget(RawAvGeneration(engine.intent().activation, seek), positionMs),
            completion,
        )
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
