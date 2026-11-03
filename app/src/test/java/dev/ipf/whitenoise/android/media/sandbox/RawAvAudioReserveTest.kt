package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.nio.ByteBuffer
import java.time.Duration

/** The device consumes real-time credits independently of renderer reads, writes and getters. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class RawAvAudioReserveTest {
    /** Ordinary video work must not starve continuously available PCM after playback begins. */
    @Test fun continuousPcmSurvivesVideoReadAndSubmissionWork() =
        runTest {
            for (speed in listOf(.25f, 1f, 2f)) {
                val fixture = Fixture(testScheduler, speed = speed)
                fixture.run()
                assertEquals("PCM starved at speed $speed", 0, fixture.sink.underruns)
                fixture.assertCompleted()
                assertTrue(fixture.sink.maximumQueuedWallUs <= 250_000)
            }
        }

    /** The production PCM output and unity Sonic path also survive the independently timed device. */
    @Test fun actualPcmOutputPreservesContinuousSamplesAndFiniteCapacity() =
        runTest {
            val fixture = Fixture(testScheduler, realOutput = true)
            fixture.run()
            assertEquals(0, fixture.sink.underruns)
            fixture.assertCompleted()
            assertTrue(fixture.sink.maximumQueuedWallUs <= 250_000)
        }

    /** A short audio tail must not hold later video until an impossible reserve has been filled. */
    @Test fun audioEofShorterThanReserveStillPacesRemainingVideo() =
        runTest {
            val fixture = Fixture(testScheduler, audioUs = 60_000, videoUs = 200_000)
            fixture.run()
            fixture.assertCompleted()
            assertTrue(fixture.owner.frameTimes.last() > fixture.owner.frameTimes.first())
        }

    /** Nonblocking partial acceptance retains the exact suffix and credits every sample once. */
    @Test fun partialWritesKeepExactSampleOrder() =
        runTest {
            val fixture = Fixture(testScheduler)
            fixture.sink.maximumWriteFrames = 17
            fixture.run()
            assertEquals(0, fixture.sink.underruns)
            fixture.assertCompleted()
        }

    /** Refused optional credit cannot prevent an already-buffered, due video frame from being submitted. */
    @Test fun softBackpressureDoesNotHoldDueVideo() =
        runTest {
            val fixture = Fixture(testScheduler)
            fixture.owner.onFrame = { pts ->
                if (pts == 0L) fixture.sink.blockUntilUs = fixture.clock.nowUs() + 200_000
            }
            fixture.run()
            fixture.assertCompleted()
            val firstSpacing = fixture.owner.frameTimes[1] - fixture.owner.frameTimes[0]
            assertTrue("Soft refill held due video for $firstSpacing us", firstSpacing <= 45_000)
        }

    /** A rate transition may refuse new credit while old output drains; the latest rate still completes. */
    @Test fun rateChangeWithTemporaryBackpressureDoesNotChaseReserve() =
        runTest {
            val fixture = Fixture(testScheduler)
            fixture.owner.onFrame = { pts ->
                if (pts == 160_000L) {
                    fixture.owner.speed = 2f
                    fixture.renderer.speed(2f)
                    fixture.sink.blockUntilUs = fixture.clock.nowUs() + 20_000
                }
            }
            fixture.run()
            fixture.assertCompleted()
            assertEquals(2f, fixture.sink.speed, 0f)
        }

    /** Revocation during the new refill read cannot write old PCM or transfer its held bitmap. */
    @Test fun refillReadRechecksPauseAccountAndSeekBeforePublication() =
        runTest {
            for (revocation in listOf("pause", "account", "seek")) {
                val fixture = Fixture(testScheduler)
                fixture.onAudioRead = {
                    when (revocation) {
                        "pause" -> fixture.owner.playing = false
                        "account" -> fixture.owner.activation++
                        "seek" -> fixture.owner.seek++
                    }
                }
                fixture.onSeek = { if (fixture.owner.seek != 0L) fixture.owner.alive = false }
                fixture.run()
                assertEquals(0, fixture.sink.accepted.size)
                assertTrue(fixture.owner.frames.isEmpty())
                assertEquals(1, fixture.audioReads)
                assertEquals(1, fixture.videoReads)
                assertFalse(fixture.owner.ended)
                assertTrue(fixture.bitmaps.all { it.isRecycled })
            }
        }

    /** Links virtual coroutine time and Android wall-clock fallback without changing production clocks. */
    private class Clock(
        private val scheduler: TestCoroutineScheduler,
    ) {
        private var previousMs = scheduler.currentTime

        /** Synchronizes Android fallback time with elapsed coroutine time, without advancing either on reads. */
        fun nowUs(): Long {
            val current = scheduler.currentTime
            ShadowSystemClock.advanceBy(Duration.ofMillis(current - previousMs))
            previousMs = current
            return current * 1000
        }

        /** Charges a bounded synchronous operation to the same independent playback clock. */
        fun work(milliseconds: Long) {
            scheduler.advanceTimeBy(milliseconds)
            nowUs()
        }
    }

    /** An independent finite device model; a write never advances the played position. */
    private class TimedSink(
        private val clock: Clock,
        private val expectedFrames: Int,
    ) : RawPcmSink {
        override val sampleRate = 8000
        override val channels = 1
        val accepted = mutableListOf<Byte>()
        var speed = 1f
            private set
        var maximumWriteFrames = Int.MAX_VALUE
        var blockUntilUs = 0L
        var maximumQueuedWallUs = 0L
            private set
        var underruns = 0
            private set
        private var playedFrames = 0.0
        private var lastUs = clock.nowUs()
        private var playing = false
        private var started = false
        private var starving = false
        private var ending = false
        private val writtenFrames: Int get() = accepted.size / 2

        /** Consumes only elapsed-time frames and records an empty queue while source samples remain available. */
        private fun update() {
            val now = clock.nowUs()
            if (playing && started) {
                val elapsedFrames = (now - lastUs) * sampleRate * speed / 1_000_000.0
                val available = writtenFrames - playedFrames
                if (elapsedFrames > available && writtenFrames < expectedFrames && !ending && !starving) {
                    underruns++
                    starving = true
                }
                playedFrames += minOf(elapsedFrames, available)
            }
            lastUs = now
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
        ): Int = write(bytes, offset, bytes.size - offset)

        /** Accepts finite whole-frame credit; refused bytes stay caller-owned and playback never jumps forward. */
        override fun write(
            bytes: ByteArray,
            offset: Int,
            byteCount: Int,
        ): Int {
            update()
            if (clock.nowUs() < blockUntilUs) return 0
            val capacityFrames = (sampleRate * speed / 4).toInt()
            val space = (capacityFrames - (writtenFrames - playedFrames)).toInt().coerceAtLeast(0)
            val count = minOf(byteCount / 2, maximumWriteFrames, space) * 2
            accepted.addAll(bytes.asList().subList(offset, offset + count))
            if (count > 0) {
                started = true
                starving = false
            }
            val queuedWallUs = ((writtenFrames - playedFrames) * 1_000_000 / sampleRate / speed).toLong()
            maximumQueuedWallUs = maxOf(maximumQueuedWallUs, queuedWallUs)
            return count
        }

        /** Marks real input exhaustion separately from steady-state starvation and polls the independent head. */
        override fun prepareForWait(endOfStream: Boolean): Long {
            update()
            if (endOfStream) ending = true
            return 1000
        }

        /** Reports played source time rather than equating it with the accepted or buffered endpoint. */
        override fun positionUs(): Long {
            update()
            return (playedFrames * 1_000_000 / sampleRate).toLong()
        }

        override fun bufferedPositionUs(): Long = writtenPositionUs()

        override fun writtenPositionUs(): Long = writtenFrames.toLong() * 1_000_000 / sampleRate

        /** Requires declared input exhaustion and actual elapsed-time consumption of every accepted frame. */
        override fun drained(): Boolean {
            update()
            return ending && playedFrames >= writtenFrames
        }

        /** Starts this finite device without fabricating any played or accepted PCM credit. */
        override fun play() {
            update()
            playing = true
        }

        /** Settles elapsed progress before suspending consumption, preserving the queued sample identity. */
        override fun pause() {
            update()
            playing = false
        }

        /** Settles the old clock before applying the new source-rate consumption ratio. */
        override fun speed(value: Float) {
            update()
            speed = value
        }

        override fun volume(value: Float) = Unit

        override fun close() = pause()
    }

    /** Finite nonblocking hardware for the real RawPcmOutput; playback does not advance on writes. */
    private class TimedDevice(
        private val sink: TimedSink,
    ) : RawPcmDevice {
        override val normalFrames = 2000
        override val capacityFrames = 2000
        override val legacyPriming = false
        override val underruns: Int get() = sink.underruns

        override fun startThresholdOne() = true

        override fun resize(frames: Int): Int = frames

        override fun headPosition(): Int = (sink.positionUs() * sink.sampleRate / 1_000_000).toInt()

        /** Advances the production-owned ByteBuffer by exactly the bytes our finite device accepted. */
        override fun write(
            buffer: ByteBuffer,
            byteCount: Int,
        ): Int {
            val bytes = ByteArray(byteCount)
            buffer.duplicate().get(bytes)
            val accepted = sink.write(bytes, 0, byteCount)
            buffer.position(buffer.position() + accepted)
            return accepted
        }

        override fun writeSilence(
            bytes: ByteArray,
            byteCount: Int,
        ): Int = sink.write(bytes, 0, byteCount)

        override fun play() = sink.play()

        override fun pause() = sink.pause()

        override fun volume(value: Float) = sink.volume(value)

        override fun close() = sink.close()
    }

    /** The finite render owner adopts and retires every bitmap, and charges finite submission work. */
    private class Owner(
        private val clock: Clock,
    ) : RawAvRenderOwner {
        var alive = true
        var playing = true
        var activation = 7L
        var seek = 0L
        var speed = 1f
        var ended = false
        val frames = mutableListOf<Long>()
        val frameTimes = mutableListOf<Long>()
        var onFrame: (Long) -> Unit = {}

        /** Supplies only the controlled generation and settings required by the production renderer. */
        override fun intent(): RawAvPlaybackIntent {
            clock.nowUs()
            return RawAvPlaybackIntent(
                alive,
                playing,
                activation,
                seek,
                0,
                speed,
                0f,
                AudioAttributes.Builder().build(),
            )
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
        ): Boolean = error("Timestamped ownership path required")

        /** Adopts each bitmap once, charges submission work, then retires the test-owned image. */
        override fun frame(
            bitmap: Bitmap,
            rotation: Int,
            activation: Long,
            seek: Long,
            timestampUs: Long,
        ): Boolean {
            frames += timestampUs
            frameTimes += clock.nowUs()
            clock.work(5)
            onFrame(timestampUs)
            bitmap.recycle()
            return true
        }

        /** Records the production renderer's actual terminal callback, never a decoder-only EOF shortcut. */
        override fun ended(
            activation: Long,
            seek: Long,
            endUs: Long,
        ) {
            ended = true
        }

        override fun failed(
            activation: Long,
            seek: Long?,
            failure: java.io.IOException,
        ) = throw failure
    }

    /** Drives actual consumeFrames through its existing source and sink seams with finite, owned inputs. */
    private class Fixture(
        scheduler: TestCoroutineScheduler,
        audioUs: Long = 1_000_000,
        private val videoUs: Long = 1_000_000,
        speed: Float = 1f,
        realOutput: Boolean = false,
    ) {
        val clock = Clock(scheduler)
        val owner = Owner(clock).also { it.speed = speed }
        val sink = TimedSink(clock, (audioUs * 8000 / 1_000_000).toInt())
        val renderer =
            RawAvRenderSession(
                ApplicationProvider.getApplicationContext(),
                Uri.EMPTY,
                true,
                true,
                owner,
            ) { _, _, _ ->
                if (realOutput) RawPcmOutput(8000, 1, TimedDevice(sink)) else sink
            }
        val bitmaps = mutableListOf<Bitmap>()
        val pcm = ByteArray((audioUs * 8000 / 1_000_000).toInt() * 2) { (it % 127).toByte() }
        var audioReads = 0
        var videoReads = 0
        var onAudioRead: () -> Unit = {}
        var onSeek: () -> Unit = {}
        private var nextVideoUs = 0L

        /** Runs actual consumeFrames with one finite preferred-track source and independently timed video work. */
        suspend fun run() {
            renderer.speed(owner.speed)
            renderer.consumeFrames(AvStreamInfo(2, 2, videoUs, 0, 8000, 1), 7, { track ->
                if (track == AvStreamProtocol.VIDEO_TRACK) {
                    videoReads++
                    clock.work(2)
                    if (nextVideoUs >= videoUs) {
                        AvStreamFrame.VideoEnd
                    } else {
                        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                        bitmaps += bitmap
                        AvStreamFrame.Video(bitmap, nextVideoUs).also { nextVideoUs += 40_000 }
                    }
                } else {
                    check(track == AvStreamProtocol.AUDIO_TRACK)
                    audioReads++
                    onAudioRead()
                    if (audioReads == 1) AvStreamFrame.Audio(pcm, 8000, 1, 0) else AvStreamFrame.AudioEnd
                }
            }, { onSeek() })
        }

        /** Verifies complete byte ordering, video ownership and terminal progress before retiring the sink. */
        fun assertCompleted() {
            assertEquals(pcm.toList(), sink.accepted)
            assertEquals(generateSequence(0L) { it + 40_000 }.takeWhile { it < videoUs }.toList(), owner.frames)
            assertTrue(owner.ended)
            assertTrue(bitmaps.all { it.isRecycled })
            renderer.pause()
        }
    }
}
