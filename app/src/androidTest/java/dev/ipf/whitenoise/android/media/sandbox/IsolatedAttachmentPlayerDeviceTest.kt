package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real parent Surface and AudioTrack playback of a synthetic local video, with no account fixture. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class IsolatedAttachmentPlayerDeviceTest {
    /** Tiny Raw Sonic Eos Produces Nonzero Pcm And Drains The Unity Device At Quarter Speed. */
    @Test
    fun tinyRawSonicEosProducesNonzeroPcmAndDrainsTheUnityDeviceAtQuarterSpeed() {
        val source = byteArrayOf(0, 32, 0, 32)
        RawSonicSegment(8000, 1, .25f).use { sonic ->
            sonic.queueInput(source, 0, source.size)
            sonic.endOfInput()
            val produced = sonic.output()
            val bytes = ByteArray(produced.remaining())
            produced.get(bytes)
            assertEquals(16, bytes.size)
            assertTrue("Explicit Sonic EOS must produce nonzero raw PCM", bytes.any { it != 0.toByte() })
            assertTrue(sonic.ended)
        }
        RawPcmOutput(
            8000,
            1,
            android.media.AudioAttributes
                .Builder()
                .build(),
        ).use { output ->
            output.speed(.25f)
            output.play()
            var offset = 0
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (offset < source.size && SystemClock.elapsedRealtime() < deadline) {
                offset += output.write(source, offset)
                SystemClock.sleep(5)
            }
            assertEquals(source.size, offset)
            while (!output.drained() && SystemClock.elapsedRealtime() < deadline) {
                output.prepareForWait(true)
                SystemClock.sleep(5)
            }
            assertTrue("Unity AudioTrack must physically consume the transformed tiny output", output.drained())
            assertEquals(250L, output.positionUs())
        }
    }

    /** Quarter Speed Raw Pcm Refills After An Interior Underrun Without Resetting The Media Clock. */
    @Test
    fun quarterSpeedRawPcmRefillsAfterAnInteriorUnderrunWithoutResettingTheMediaClock() {
        RawPcmOutput(
            8000,
            1,
            android.media.AudioAttributes
                .Builder()
                .build(),
        ).use { output ->
            output.speed(.25f)
            output.play()
            val source = ByteArray(640) { if (it % 2 == 1) 32 else 0 }
            var previous = 0L
            repeat(2) { burst ->
                val beforeBurst = output.positionUs()
                var offset = 0
                val deadline = SystemClock.elapsedRealtime() + 8000
                while (offset < source.size && SystemClock.elapsedRealtime() < deadline) {
                    offset += output.write(source, offset)
                    output.prepareForWait(false)
                    assertTrue(output.positionUs() >= previous)
                    previous = output.positionUs()
                    SystemClock.sleep(5)
                }
                assertEquals(source.size, offset)
                // Exhaust the currently submitted device queue, then refill that same track.
                repeat(80) {
                    output.prepareForWait(false)
                    SystemClock.sleep(5)
                }
                assertTrue("Nonterminal burst $burst must advance before EOS", output.positionUs() > beforeBurst)
                assertTrue(
                    "Submitted PCM must catch up before the next refill",
                    output.positionUs() + 1000 >= output.bufferedPositionUs(),
                )
                SystemClock.sleep(100)
            }
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!output.drained() && SystemClock.elapsedRealtime() < deadline) {
                output.prepareForWait(true)
                SystemClock.sleep(5)
            }
            assertTrue(output.drained())
            assertEquals(80_000L, output.positionUs())
        }
    }

    /** Paused Seeking Speed Pause And Repeat Use Real Raw Playback. */
    @Test
    @Suppress("LongMethod") // One fixture owns the player, display and selected file through controls and cleanup.
    fun pausedSeekingSpeedPauseAndRepeatUseRealRawPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        IsolatedMediaDecoder.install(context)
        assertMalformedContainerInvalidatesCache(context)
        assertUnsupportedSampleEntryPreservesCache(context)
        val source = File.createTempFile("sandbox-controls-", ".mp4", context.cacheDir)
        var scenario: ActivityScenario<ComponentActivity>? = null
        val player = AtomicReference<IsolatedAttachmentPlayer?>()
        val surface = AtomicReference<SurfaceView?>()
        val failure = AtomicReference<androidx.media3.common.PlaybackException?>()
        try {
            instrumentation.context.assets.open("sandbox_av_controls.mp4").use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            scenario = ActivityScenario.launch(ComponentActivity::class.java)
            scenario.onActivity { activity ->
                val view = SurfaceView(activity)
                val container = FrameLayout(activity)
                container.addView(view, FrameLayout.LayoutParams(SURFACE_WIDTH, SURFACE_HEIGHT, Gravity.CENTER))
                activity.setContentView(container)
                surface.set(view)
                val created = IsolatedAttachmentPlayer(context, android.net.Uri.fromFile(source))
                player.set(created)
                created.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            failure.set(error)
                        }
                    },
                )
                created.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
                created.volume = 0f
                created.setVideoSurfaceView(view)
                created.prepare()
            }
            await("paused preparation", failure) { player.get()?.playbackState == Player.STATE_READY }
            val view = checkNotNull(surface.get())
            awaitColor(view, Color.RED, failure)
            pausedSeekWithReattachment(player, view, failure, BLUE_POSITION_MS, Color.BLUE)
            pausedSeek(player, view, failure, RED_POSITION_MS, Color.RED)
            onMain {
                checkNotNull(player.get()).setPlaybackSpeed(2f)
                checkNotNull(player.get()).play()
            }
            await("warm audio/video clock", failure) {
                player.get()?.let { it.isPlaying && it.currentPosition in 1250L..3000L } == true
            }
            assertDoubleSpeedProgression(player, failure)
            val pausedAt = AtomicLong()
            onMain {
                val live = checkNotNull(player.get())
                live.pause()
                pausedAt.set(live.currentPosition)
                assertEquals(2f, live.playbackParameters.speed, 0.001f)
                assertEquals(0f, live.volume, 0f)
            }
            SystemClock.sleep(150)
            onMain {
                val live = checkNotNull(player.get())
                assertFalse(live.isPlaying)
                assertTrue(kotlin.math.abs(live.currentPosition - pausedAt.get()) <= 30)
            }
            // Alternate actual pixels again so a stale blue Surface cannot satisfy the next blue seek.
            pausedSeek(player, view, failure, RED_POSITION_MS, Color.RED)
            pausedSeek(player, view, failure, BLUE_POSITION_MS, Color.BLUE)
            onMain {
                val live = checkNotNull(player.get())
                live.stop()
                assertEquals(Player.STATE_IDLE, live.playbackState)
                assertTrue(kotlin.math.abs(live.currentPosition - BLUE_POSITION_MS) <= 20)
                live.prepare()
            }
            await("paused preparation after stop", failure) { player.get()?.playbackState == Player.STATE_READY }
            onMain {
                val live = checkNotNull(player.get())
                assertFalse(live.playWhenReady)
                assertTrue(kotlin.math.abs(live.currentPosition - BLUE_POSITION_MS) <= 20)
            }
            awaitColor(view, Color.BLUE, failure)
            onMain {
                val live = checkNotNull(player.get())
                live.repeatMode = Player.REPEAT_MODE_ONE
                live.play()
            }
            // Observe progress in the tail before accepting a later wrap; cached zero cannot count as repeat.
            await("repeat reaches blue tail", failure) {
                player.get()?.let { it.isPlaying && it.currentPosition in 6500L..7900L } == true
            }
            await("repeat wraps source position", failure) {
                player.get()?.let { it.isPlaying && it.currentPosition < 2000 } == true
            }
            awaitColor(view, Color.RED, failure)
            onMain {
                checkNotNull(player.get()).pause()
                assertFalse(checkNotNull(player.get()).isPlaying)
            }
        } finally {
            try {
                onMain { player.getAndSet(null)?.release() }
            } finally {
                try {
                    scenario?.close()
                } finally {
                    check(source.delete()) { "Synthetic selected video cleanup failed" }
                }
            }
        }
    }

    /** Pixel Copy Buffer Outlives Timed Out Request Until Its Callback. */
    @Test
    fun pixelCopyBufferOutlivesTimedOutRequestUntilItsCallback() {
        val timedOut = PixelSample()
        timedOut.releaseWhenSafe(requested = true)
        assertFalse(timedOut.bitmap.isRecycled)
        timedOut.finish(PixelCopy.ERROR_TIMEOUT)
        assertTrue(timedOut.bitmap.isRecycled)
        val completed = PixelSample()
        completed.finish(PixelCopy.SUCCESS)
        assertFalse(completed.bitmap.isRecycled)
        completed.releaseWhenSafe(requested = true)
        assertTrue(completed.bitmap.isRecycled)
    }

    /** Truncated recognized MP4 must cross native OPEN, primitive refusal and the player adapter. */
    private fun assertMalformedContainerInvalidatesCache(context: android.content.Context) {
        // Complete ftyp box with no required moov box: recognized MP4 with invalid container data.
        assertContainerFailure(
            context,
            byteArrayOf(
                0,
                0,
                0,
                24,
                102,
                116,
                121,
                112,
                105,
                115,
                111,
                109,
                0,
                0,
                0,
                0,
                105,
                115,
                111,
                109,
                109,
                112,
                52,
                50,
            ),
            invalidates = true,
        )
    }

    /** Keep the MP4 boxes intact but declare MPEG-4 Part 2, outside the native codec allowlist. */
    private fun assertUnsupportedSampleEntryPreservesCache(context: android.content.Context) {
        val bytes =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("native-av-corpus/red-h264.mp4")
                .use { it.readBytes() }
        val stsd = byteArrayOf(115, 116, 115, 100)
        val matches =
            (0..bytes.size - stsd.size).filter { offset ->
                stsd.indices.all { bytes[offset + it] == stsd[it] }
            }
        assertEquals("Fixture must contain one sample description box", 1, matches.size)
        val type = matches.single() + 16 // type, fullbox flags/version, count, entry size, entry type.
        assertTrue(type + 4 <= bytes.size)
        assertTrue(bytes.copyOfRange(type, type + 4).contentEquals(byteArrayOf(97, 118, 99, 49)))
        byteArrayOf(109, 112, 52, 118).copyInto(bytes, type) // mp4v, refused before compressed packet decode.
        assertContainerFailure(context, bytes, invalidates = false)
    }

    private fun assertContainerFailure(
        context: android.content.Context,
        bytes: ByteArray,
        invalidates: Boolean,
    ) {
        val source = File.createTempFile("sandbox-container-refusal-", ".mp4", context.cacheDir)
        val player = AtomicReference<IsolatedAttachmentPlayer?>()
        val failure = AtomicReference<androidx.media3.common.PlaybackException?>()
        try {
            source.writeBytes(bytes)
            onMain {
                val created = IsolatedAttachmentPlayer(context, android.net.Uri.fromFile(source))
                player.set(created)
                created.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            failure.set(error)
                        }
                    },
                )
                created.prepare()
                created.play()
            }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (failure.get() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            val error = requireNotNull(failure.get()) { "Selected fixture must refuse playback" }
            val expected =
                if (invalidates) {
                    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                } else {
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED
                }
            assertEquals(expected, error.errorCode)
            assertEquals(
                invalidates,
                dev.ipf.whitenoise.android.media
                    .playbackErrorInvalidatesAttachmentCache(error),
            )
        } finally {
            onMain { player.getAndSet(null)?.release() }
            check(source.delete()) { "Container fixture cleanup failed" }
        }
    }

    /** Reattaches the actual output during a paused seek and verifies queued pixels, not physical presentation. */
    private fun pausedSeekWithReattachment(
        player: AtomicReference<IsolatedAttachmentPlayer?>,
        view: SurfaceView,
        failure: AtomicReference<androidx.media3.common.PlaybackException?>,
        positionMs: Long,
        color: Int,
    ) {
        onMain {
            val live = checkNotNull(player.get())
            live.clearVideoSurfaceView(view)
            live.seekTo(positionMs)
            live.setVideoSurfaceView(view)
        }
        await("reattached paused seek target", failure) {
            player.get()?.let { !it.playWhenReady && kotlin.math.abs(it.currentPosition - positionMs) <= 20 } == true
        }
        awaitColor(view, color, failure)
    }

    /** Requests a seek while playback is paused and waits for the expected visible frame. */
    private fun pausedSeek(
        player: AtomicReference<IsolatedAttachmentPlayer?>,
        view: SurfaceView,
        failure: AtomicReference<androidx.media3.common.PlaybackException?>,
        positionMs: Long,
        color: Int,
    ) {
        onMain { checkNotNull(player.get()).seekTo(positionMs) }
        await("paused seek target", failure) {
            player.get()?.let { !it.playWhenReady && kotlin.math.abs(it.currentPosition - positionMs) <= 20 } == true
        }
        awaitColor(view, color, failure)
        onMain { assertFalse(checkNotNull(player.get()).isPlaying) }
    }

    /** Assert Double Speed Progression. */
    private fun assertDoubleSpeedProgression(
        player: AtomicReference<IsolatedAttachmentPlayer?>,
        failure: AtomicReference<androidx.media3.common.PlaybackException?>,
    ) {
        val first = positionSample(player)
        SystemClock.sleep(300)
        val second = positionSample(player)
        failure.get()?.let { throw AssertionError("Raw playback failed during speed measurement", it) }
        val elapsed = second.first - first.first
        check(elapsed > 0)
        val rate = (second.second - first.second).toDouble() / elapsed
        assertTrue("Actual audio-master position did not advance near 2x: $rate", rate in 1.4..2.6)
    }

    /** Captures a playback-position sample for bounded progression assertions. */
    private fun positionSample(player: AtomicReference<IsolatedAttachmentPlayer?>): Pair<Long, Long> {
        val sample = AtomicReference<Pair<Long, Long>>()
        onMain {
            val live = checkNotNull(player.get())
            assertTrue(live.isPlaying)
            sample.set(SystemClock.elapsedRealtime() to live.currentPosition)
        }
        return sample.get()
    }

    /**
     * Waits for the fixture condition within its finite test deadline and fails when the expected event never
     * arrives.
     */
    private fun await(
        operation: String,
        failure: AtomicReference<androidx.media3.common.PlaybackException?>,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            failure.get()?.let { throw AssertionError("Raw playback failed during $operation", it) }
            val satisfied = AtomicReference(false)
            onMain { satisfied.set(condition()) }
            if (satisfied.get()) return
            SystemClock.sleep(20)
        }
        throw AssertionError("Timed out: $operation")
    }

    /**
     * Waits for the expected rendered fixture color rather than assuming the first callback already displayed
     * it.
     */
    private fun awaitColor(
        view: SurfaceView,
        expected: Int,
        failure: AtomicReference<androidx.media3.common.PlaybackException?>,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) {
            failure.get()?.let { throw AssertionError("Raw playback failed before its Surface frame", it) }
            val pixel = copyCenter(view)
            if (matchesColor(pixel, expected)) {
                return
            }
            SystemClock.sleep(30)
        }
        throw AssertionError("Expected synthetic frame color was not rendered on the actual Surface")
    }

    /**
     * Compares sampled channels with the fixture tolerance instead of requiring byte-identical display
     * conversion.
     */
    private fun matchesColor(
        pixel: Int?,
        expected: Int,
    ): Boolean {
        if (pixel == null) return false
        return kotlin.math.abs(Color.red(pixel) - Color.red(expected)) < 50 &&
            kotlin.math.abs(Color.green(pixel) - Color.green(expected)) < 50 &&
            kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) < 50
    }

    private class PixelSample {
        val bitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val completed = CountDownLatch(1)
        val status = AtomicInteger(-1)
        private val finished = AtomicBoolean()
        private val abandoned = AtomicBoolean()
        private val recycled = AtomicBoolean()

        fun finish(result: Int) {
            status.set(result)
            finished.set(true)
            completed.countDown()
            if (abandoned.get()) recycleOnce()
        }

        /** Defers fixture-buffer release until the asynchronous callback can no longer access it. */
        fun releaseWhenSafe(requested: Boolean) {
            abandoned.set(true)
            if (!requested || finished.get()) recycleOnce()
        }

        /** Recycles the owned bitmap at most once, preserving callbacks that still retain it. */
        private fun recycleOnce() {
            if (recycled.compareAndSet(false, true)) bitmap.recycle()
        }
    }

    /** Samples the rendered center pixel through PixelCopy without assuming synchronous completion. */
    private fun copyCenter(view: SurfaceView): Int? {
        val rectangle = AtomicReference<Rect?>()
        onMain {
            if (view.width > 0 && view.height > 0 && view.holder.surface.isValid) {
                val x = view.width / 2
                val y = view.height / 2
                rectangle.set(Rect(x, y, x + 1, y + 1))
            }
        }
        val source = rectangle.get() ?: return null
        val sample = PixelSample()
        var requested = false
        try {
            onMain {
                PixelCopy.request(view, source, sample.bitmap, sample::finish, Handler(Looper.getMainLooper()))
                requested = true
            }
            check(sample.completed.await(2, TimeUnit.SECONDS)) { "PixelCopy did not complete" }
            return if (sample.status.get() == PixelCopy.SUCCESS) sample.bitmap.getPixel(0, 0) else null
        } finally {
            // After timeout an accepted request owns its bitmap until its callback; never recycle under native copy.
            sample.releaseWhenSafe(requested)
        }
    }

    /** Runs the fixture action on the Android main thread and returns its completed result. */
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private companion object {
        const val SURFACE_WIDTH = 320
        const val SURFACE_HEIGHT = 240
        const val RED_POSITION_MS = 1000L
        const val BLUE_POSITION_MS = 6000L
    }
}
