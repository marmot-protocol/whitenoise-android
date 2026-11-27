package dev.ipf.whitenoise.android.media.sandbox

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.ExecutionException

/** Exercises the actual fenced sink path at the adoption/render boundary without worker timing races. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], shadows = [RecordingCanvasSurface::class])
class RawAvSeekPublicationTest {
    /** Display work runs only when a test renders, keeping adoption and drawing separately schedulable. */
    private val display = QueuedExecutor()

    /** An adopted bitmap from seek zero cannot submit or credit first output after seek one. */
    @Test fun adoptedOldSeekCannotPublishAfterSeekAndMatchingNewFrameStillSubmits() {
        withEngine { engine ->
            set(engine, "playIntent", true)
            val old = bitmap()
            adopt(engine, old)
            set(engine, "width", 2)
            val completion = engine.seekToAsync(1500)
            val intent = engine.intent()
            engine.seeked(intent.activation, intent.seek)
            assertFalse(completion.isDone)
            render(engine)
            assertEquals(0, RecordingCanvasSurface.posts)
            assertEquals(0L, engine.snapshot().renderedFrame)
            assertTrue(old.isRecycled)
            val matching = bitmap()
            assertTrue(engine.frame(matching, 0, intent.activation, intent.seek))
            assertTrue(completion.isDone)
            assertEquals(1500L, engine.positionMs())
            render(engine)
            assertEquals(1, RecordingCanvasSurface.posts)
            assertEquals(1L, engine.snapshot().renderedFrame)
            assertTrue(old.isRecycled)
            assertFalse(matching.isRecycled)
            engine.close()
            assertTrue(matching.isRecycled)
        }
    }

    /** Stopping invalidates the queued post while retaining the frame for an explicit redraw. */
    @Test fun queuedFrameDoesNotPostAfterStop() {
        withEngine { engine ->
            val queued = bitmap()
            adopt(engine, queued)
            engine.stop()

            render(engine)

            assertEquals(0, RecordingCanvasSurface.posts)
            assertEquals(0L, engine.snapshot().renderedFrame)
            assertFalse(queued.isRecycled)
        }
    }

    /** A retired account's queued frame cannot post after the attached surface is cleared. */
    @Test fun queuedFrameCannotPostAfterAccountRetirement() {
        withEngine { engine ->
            val queued = bitmap()
            adopt(engine, queued)
            assertEquals(0, RecordingCanvasSurface.posts)

            RawAvPlaybackEngine.retireAll()

            assertEquals(1, RecordingCanvasSurface.posts) // The synchronous black clear.
            render(engine)
            assertEquals(1, RecordingCanvasSurface.posts)
            assertEquals(0L, engine.snapshot().renderedFrame)
            assertTrue(queued.isRecycled)
        }
    }

    /** A replacement surface shows the committed frame while the current paused seek is still in flight. */
    @Test fun pausedSeekReattachmentDrawsCommittedPosterBeforeMatchingPoster() {
        withSurface { surface ->
            withEngine { engine ->
                val old = bitmap()
                adopt(engine, old)
                render(engine)
                val completion = engine.seekToAsync(1500) as SettableFuture<Void?>
                assertEquals(0L, engine.positionMs())
                engine.attachVideo(surface)
                RecordingCanvasSurface.posts = 0 // Attachment clears are separate from retained-frame publication.
                render(engine)
                assertEquals(1, RecordingCanvasSurface.posts)
                assertEquals(2L, engine.snapshot().renderedFrame)
                assertFalse(completion.isDone)
                assertEquals(0L, engine.positionMs())
                val intent = engine.intent()
                val request =
                    RawAvPausedSeekRequest(
                        RawAvPosterTarget(RawAvGeneration(intent.activation, intent.seek), 1500),
                        completion,
                    )
                val matching = bitmap()
                assertTrue(engine.posterResult(request, MediaProbe(2, 2, 4000, matching), null))
                assertTrue(completion.isDone)
                assertEquals(1500L, engine.positionMs())
                render(engine)
                assertEquals(2, RecordingCanvasSurface.posts)
                assertEquals(3L, engine.snapshot().renderedFrame)
                assertTrue(old.isRecycled)
                assertFalse(matching.isRecycled)
                engine.close()
                assertTrue(matching.isRecycled)
            }
        }
    }

    @Test fun pausedSeekPreservesTheQueuedRedrawOnAReplacementSurface() {
        withSurface { replacement ->
            withEngine { engine ->
                val old = bitmap()
                adopt(engine, old)
                render(engine)

                engine.attachVideo(replacement)
                val completion = engine.seekToAsync(1500)
                RecordingCanvasSurface.posts = 0
                render(engine)

                assertEquals(1, RecordingCanvasSurface.posts)
                assertEquals(2L, engine.snapshot().renderedFrame)
                assertFalse(old.isRecycled)
                assertFalse(completion.isDone)
                assertEquals(0L, engine.positionMs())
            }
        }
    }

    @Test fun pausingBeforeTheTargetFrameKeepsTheSeekAndLetsTheUserRetry() {
        withSurface { surface ->
            withEngine { engine ->
                set(engine, "playIntent", true)
                set(engine, "width", 2)
                set(engine, "pausedUs", 800000L)
                val old = bitmap()
                adopt(engine, old)
                render(engine)
                val completion = engine.seekToAsync(1500)
                val playing = engine.intent()
                engine.seeked(playing.activation, playing.seek)
                engine.attachVideo(surface)
                RecordingCanvasSurface.posts = 0
                render(engine)
                assertEquals(0, RecordingCanvasSurface.posts)
                engine.setPlaying(false)
                render(engine)
                assertEquals(1, RecordingCanvasSurface.posts)
                assertEquals(2L, engine.snapshot().renderedFrame)

                val request = takeQueuedPoster(engine)
                assertTrue(request.explicit)
                assertEquals(1500L, request.target.positionMs)
                assertSame(completion, request.completion)
                assertFalse(completion.isDone)
                assertFalse(engine.snapshot().playWhenReady)
                assertEquals(800L, engine.positionMs())
                assertFalse(engine.posterResult(request, null, null))
                val refusal = assertThrows(ExecutionException::class.java) { completion.get() }
                assertSame(refusal.cause, engine.snapshot().error)
                val failedSeek = refusal.cause as RawPausedSeekFailure
                assertEquals(1500L, failedSeek.positionMs)
                assertEquals(800L, engine.positionMs())
                assertEquals(2L, engine.snapshot().renderedFrame)
                engine.attachVideo(surface)
                RecordingCanvasSurface.posts = 0
                render(engine)
                assertEquals(1, RecordingCanvasSurface.posts)
                assertFalse(old.isRecycled)

                val retry = engine.seekToAsync(failedSeek.positionMs) as SettableFuture<Void?>
                render(engine)
                assertEquals(1, RecordingCanvasSurface.posts)
                val current = engine.intent()
                val generation = RawAvGeneration(current.activation, current.seek)
                val retryTarget = RawAvPosterTarget(generation, failedSeek.positionMs)
                val retryRequest = RawAvPausedSeekRequest(retryTarget, retry)
                assertTrue(engine.posterResult(retryRequest, MediaProbe(2, 2, 4000, bitmap()), null))
                assertEquals(1500L, engine.positionMs())
                assertNull(engine.snapshot().error)
                render(engine)
                assertEquals(2, RecordingCanvasSurface.posts)
                assertTrue(old.isRecycled)
            }
        }
    }

    @Test fun exhaustionBeforeAnyUsableVideoFrameReportsTheOriginalSeek() {
        withEngine { engine ->
            set(engine, "playIntent", true)
            set(engine, "width", 2)
            set(engine, "pausedUs", 800_000L)
            val completion = engine.seekToAsync(1500)
            val intent = engine.intent()
            engine.videoExhausted(intent.activation, intent.seek - 1)
            assertFalse(completion.isDone)
            engine.videoExhausted(intent.activation, intent.seek)
            val refusal = assertThrows(ExecutionException::class.java) { completion.get() }
            assertSame(refusal.cause, engine.snapshot().error)
            assertEquals(1500L, (refusal.cause as RawPausedSeekFailure).positionMs)
            assertFalse(engine.snapshot().playWhenReady)
            assertEquals(800L, engine.positionMs())
        }
    }

    /**
     * Stops background consumers so the test explicitly schedules the real private adoption/render boundary.
     * The worker's cancellation `finally` recycles the retained frame, so it must finish before any adoption —
     * a bare cancel let it run later on an IO thread and recycle a bitmap the test still asserts on.
     */
    private fun withEngine(block: (RawAvPlaybackEngine) -> Unit) =
        withSurface { surface ->
            val engine =
                RawAvPlaybackEngine(
                    RuntimeEnvironment.getApplication(),
                    Uri.parse("file:///synthetic.mp4"),
                    listener =
                        object : RawAvPlaybackEngine.Listener {
                            override fun onChanged() = Unit
                        },
                    prepareProbe = { null },
                    displayExecutor = display,
                )
            val worker = checkNotNull((field(engine, "scope") as CoroutineScope).coroutineContext[Job])
            runBlocking { worker.cancelAndJoin() }
            set(engine, "durationUs", 4_000_000L)
            try {
                engine.attachVideo(surface)
                RecordingCanvasSurface.posts = 0
                block(engine)
            } finally {
                engine.close()
            }
        }

    /** Keeps each platform target alive until the engine using it has closed. */
    private fun withSurface(block: (Surface) -> Unit) {
        val texture = SurfaceTexture(0)
        try {
            val surface = Surface(texture)
            try {
                block(surface)
            } finally {
                surface.release()
            }
        } finally {
            texture.release()
        }
    }

    /** Creates an owned frame whose recycling is checked at replacement and owner retirement. */
    private fun bitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    /** Pauses after the production adoption helper and before its separate render lock acquisition. */
    private fun adopt(
        engine: RawAvPlaybackEngine,
        bitmap: Bitmap,
    ) {
        engine.javaClass
            .getDeclaredMethod(
                "retainFrame",
                Bitmap::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
            .invoke(engine, bitmap, 0, -1L)
    }

    /** Runs the production display-thread draws queued so far, including the actual RawVideoOutput sink. */
    @Suppress("UnusedParameter") // Keeps call sites naming the engine whose queued draws run.
    private fun render(engine: RawAvPlaybackEngine) = display.runAll()

    /** Reads the actual request published by pause with background workers stopped. */
    @Suppress("UNCHECKED_CAST")
    private fun takeQueuedPoster(engine: RawAvPlaybackEngine): RawAvPausedSeekRequest {
        val poster = checkNotNull(field(engine, "pausedPoster"))
        val requests = field(poster, "requests") as Channel<RawAvPausedSeekRequest>
        return requests.tryReceive().getOrThrow()
    }

    /** Reads fixture ownership without opening an alternate renderer or parser path. */
    private fun field(
        owner: Any,
        name: String,
    ): Any? =
        owner.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner)

    /** Seeds only the controlled control state required for this interleaving. */
    private fun set(
        engine: RawAvPlaybackEngine,
        name: String,
        value: Any,
    ) {
        engine.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(engine, value)
    }
}

/** A deterministic platform canvas, leaving RawVideoOutput and all ownership fences in the path. */
@Implements(Surface::class)
class RecordingCanvasSurface {
    private var canvasBitmap: Bitmap? = null

    /** Matches the platform method; this fixture always provides a valid render target. */
    @Suppress("FunctionOnlyReturningConstant")
    @Implementation
    fun isValid(): Boolean = true

    /** Supplies a tiny real software canvas to the production CPU draw operation. */
    @Suppress("UnusedParameter") // Required platform signature; the sink requests a full canvas.
    @Implementation
    fun lockCanvas(dirty: android.graphics.Rect?): Canvas {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        canvasBitmap = bitmap
        return Canvas(bitmap)
    }

    /** Counts actual sink posts, including explicit clears separately reset by the fixture. */
    @Implementation fun unlockCanvasAndPost(canvas: Canvas) {
        posts++
        try {
            canvas.setBitmap(null)
        } finally {
            canvasBitmap?.recycle()
            canvasBitmap = null
        }
    }

    companion object {
        var posts = 0
    }
}

/** Collects display tasks so a test decides exactly when queued draws run. */
private class QueuedExecutor : java.util.concurrent.Executor {
    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}
