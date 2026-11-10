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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Exercises the actual fenced sink path at the adoption/render boundary without worker timing races. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], shadows = [RecordingCanvasSurface::class])
class RawAvSeekPublicationTest {
    /** An adopted bitmap from seek zero cannot submit or credit first output after seek one. */
    @Test fun adoptedOldSeekCannotPublishAfterSeekAndMatchingNewFrameStillSubmits() {
        withEngine { engine ->
            set(engine, "playIntent", true)
            val old = bitmap()
            adopt(engine, old)
            engine.seekToAsync(1500)
            render(engine)
            assertEquals(0, RecordingCanvasSurface.posts)
            assertEquals(0L, engine.snapshot().renderedFrame)
            assertFalse(old.isRecycled)
            val matching = bitmap()
            val intent = engine.intent()
            assertTrue(engine.frame(matching, 0, intent.activation, intent.seek))
            assertEquals(1, RecordingCanvasSurface.posts)
            assertEquals(1L, engine.snapshot().renderedFrame)
            assertTrue(old.isRecycled)
            assertFalse(matching.isRecycled)
            engine.close()
            assertTrue(matching.isRecycled)
        }
    }

    /** Surface reattachment while a paused poster is pending must not redraw the old seek's retained pixels. */
    @Test fun pausedSeekReattachmentRejectsOldPosterAndAcceptsMatchingPoster() {
        withSurface { surface ->
            withEngine { engine ->
                val old = bitmap()
                adopt(engine, old)
                val completion = engine.seekToAsync(1500) as SettableFuture<Void>
                engine.attachVideo(surface)
                RecordingCanvasSurface.posts = 0 // Attachment clears are separate from retained-frame publication.
                render(engine)
                assertEquals(0, RecordingCanvasSurface.posts)
                assertEquals(0L, engine.snapshot().renderedFrame)
                val intent = engine.intent()
                val request =
                    RawAvPausedSeekRequest(
                        RawAvPosterTarget(RawAvGeneration(intent.activation, intent.seek), 1500),
                        completion,
                    )
                val matching = bitmap()
                assertTrue(engine.posterResult(request, MediaProbe(2, 2, 4000, matching)))
                assertTrue(completion.isDone)
                render(engine)
                assertEquals(1, RecordingCanvasSurface.posts)
                assertEquals(1L, engine.snapshot().renderedFrame)
                assertTrue(old.isRecycled)
                assertFalse(matching.isRecycled)
                engine.close()
                assertTrue(matching.isRecycled)
            }
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
            .getDeclaredMethod("retainFrame", Bitmap::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(engine, bitmap, 0)
    }

    /** Invokes the production guarded render path, including the actual RawVideoOutput sink. */
    private fun render(engine: RawAvPlaybackEngine) {
        engine.javaClass
            .getDeclaredMethod("renderRetained")
            .apply { isAccessible = true }
            .invoke(engine)
    }

    /** Reads fixture ownership without opening an alternate renderer or parser path. */
    private fun field(
        engine: RawAvPlaybackEngine,
        name: String,
    ): Any? =
        engine.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(engine)

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
