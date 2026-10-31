package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class RawAvPreferredRenderTest {
    @Test fun audioOnlyInitialTimestampGapAndSeekGapRetainTheSelectedSourceAnchor() =
        runBlocking {
            for (target in listOf(0L, 20_000L)) {
                val owner = Owner().apply { targetUs = target }
                val device = RawPcmOutputSonicTest.Device()
                val session =
                    RawAvRenderSession(
                        ApplicationProvider.getApplicationContext(),
                        Uri.EMPTY,
                        false,
                        true,
                        owner,
                    ) { rate, channels, _ -> RawPcmOutput(rate, channels, device) }
                var reads = 0
                session.consumeFrames(AvStreamInfo(0, 0, 60_000, 0, 8000, 1), 7, { track ->
                    assertEquals(AvStreamProtocol.AUDIO_TRACK, track)
                    if (reads++ == 0) {
                        AvStreamFrame.Audio(ByteArray(160) { if (it % 2 == 1) 32 else 0 }, 8000, 1, 50_000)
                    } else {
                        AvStreamFrame.AudioEnd
                    }
                }, { assertEquals(target, it) })
                assertEquals(((60_000 - target) * 16000 / 1_000_000).toInt(), device.audio.size())
                val accepted = device.audio.toByteArray()
                val gapBytes = ((50_000 - target) * 16000 / 1_000_000).toInt()
                assertTrue(accepted.take(gapBytes).all { it == 0.toByte() })
                assertTrue(accepted.drop(gapBytes).any { it != 0.toByte() })
                assertEquals(60_000L, session.positionUs())
                assertTrue(owner.ended)
                session.pause()
            }
        }

    @Test fun largePcmPacketFeedsInTenMillisecondChunksWithoutDroppingOrdinaryVideoFrames() =
        runBlocking {
            val owner = Owner()
            val sink = Sink()
            val session = session(owner, sink)
            val video = ArrayDeque((0..12).map { video(it * 10_000L) })
            var audioReads = 0
            session.consumeFrames(info(), 7, { track ->
                if (track == AvStreamProtocol.VIDEO_TRACK) {
                    video.removeFirstOrNull() ?: AvStreamFrame.VideoEnd
                } else if (audioReads++ == 0) {
                    AvStreamFrame.Audio(ByteArray(1920), 8000, 1, 0)
                } else {
                    AvStreamFrame.AudioEnd
                }
            }, {})
            assertEquals((0..12).map { it * 10_000L }, owner.displayed)
            assertEquals(2, audioReads)
            assertTrue(sink.largestWrite <= 160)
            assertEquals(1920, sink.accepted)
            assertTrue(owner.ended)
        }

    @Test fun realQuarterSpeedCoordinatorPacesEveryVideoAcrossOneLargeNativePcmBlock() =
        runBlocking {
            val owner = Owner()
            val device = RawPcmOutputSonicTest.Device()
            val session =
                RawAvRenderSession(
                    ApplicationProvider.getApplicationContext(),
                    Uri.EMPTY,
                    true,
                    true,
                    owner,
                ) { rate, channels, _ -> RawPcmOutput(rate, channels, device) }
            session.speed(.25f)
            val video = ArrayDeque((0..12).map { video(it * 10_000L) })
            var audioReads = 0
            session.consumeFrames(info(), 7, { track ->
                if (track == AvStreamProtocol.VIDEO_TRACK) {
                    video.removeFirstOrNull() ?: AvStreamFrame.VideoEnd
                } else if (audioReads++ == 0) {
                    AvStreamFrame.Audio(ByteArray(1920) { if (it % 2 == 1) 32 else 0 }, 8000, 1, 0)
                } else {
                    AvStreamFrame.AudioEnd
                }
            }, {})
            assertEquals((0..12).map { it * 10_000L }, owner.displayed)
            assertEquals(2, audioReads)
            assertTrue(device.audio.toByteArray().any { it != 0.toByte() })
            assertTrue(owner.ended)
            session.pause()
            assertTrue(device.closeAttempted)
        }

    @Test fun failingWaitRecyclesOnlyTheUnadoptedBitmap() =
        runBlocking {
            val owner = Owner()
            val sink = Sink().apply { failWaitAt = 3 }
            val session = session(owner, sink)
            val bitmap = video(10_000)
            var videoRead = false
            try {
                session.consumeFrames(info(), 7, { track ->
                    if (track == AvStreamProtocol.VIDEO_TRACK && !videoRead) {
                        bitmap.also { videoRead = true }
                    } else {
                        AvStreamFrame.Audio(ByteArray(160), 8000, 1, 0)
                    }
                }, {})
                fail("Expected typed output refusal")
            } catch (_: RawOutputFailure) {
                assertTrue(bitmap.bitmap.isRecycled)
                assertTrue(owner.displayed.isEmpty())
                assertFalse(owner.ended)
            }
        }

    @Test fun cancellationDuringWaitRecyclesTheUnadoptedFrame() =
        runBlocking {
            val owner = Owner()
            val sink = Sink().apply { cancelAt = 3 }
            val session = session(owner, sink)
            val bitmap = video(10_000)
            try {
                session.consumeFrames(info(), 7, { track ->
                    if (track == AvStreamProtocol.VIDEO_TRACK) {
                        bitmap
                    } else {
                        AvStreamFrame.Audio(ByteArray(160), 8000, 1, 0)
                    }
                }, {})
                fail("Expected canceled wait")
            } catch (_: kotlinx.coroutines.CancellationException) {
                assertTrue(bitmap.bitmap.isRecycled)
                assertTrue(owner.displayed.isEmpty())
                assertFalse(owner.ended)
            }
        }

    @Test fun callbackAdoptedBitmapSurvivesExceptionalOwnerReturn() =
        runBlocking {
            val owner = Owner().apply { throwAfterAdoption = true }
            val session = session(owner, Sink())
            val bitmap = video(0)
            try {
                session.consumeFrames(info(), 7, { bitmap }, {})
                fail("Expected owner failure")
            } catch (_: IOException) {
                assertFalse(bitmap.bitmap.isRecycled)
                assertEquals(listOf(0L), owner.displayed)
                bitmap.bitmap.recycle()
            }
        }

    private fun session(
        owner: Owner,
        sink: Sink,
    ) = RawAvRenderSession(
        ApplicationProvider.getApplicationContext(),
        Uri.EMPTY,
        true,
        true,
        owner,
    ) { _, _, _ -> sink }

    private fun info() = AvStreamInfo(2, 2, 120_000, 0, 8000, 1)

    private fun video(time: Long): AvStreamFrame.Video {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff000000.toInt() or time.toInt())
        return AvStreamFrame.Video(bitmap, time)
    }

    private class Owner : RawAvRenderOwner {
        val displayed = mutableListOf<Long>()
        var ended = false
        var targetUs = 0L
        var throwAfterAdoption = false

        override fun intent(): RawAvPlaybackIntent {
            val attributes = AudioAttributes.Builder().build()
            return RawAvPlaybackIntent(true, true, 7, 1, targetUs, 1f, 1f, attributes)
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
        ): Boolean {
            displayed += (bitmap.getPixel(0, 0) and 0x00ffffff).toLong()
            if (throwAfterAdoption) throw IOException("Owner retained this bitmap before failure")
            bitmap.recycle()
            return true
        }

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
            failure: IOException,
        ) = Unit
    }

    private class Sink : RawPcmSink {
        override val sampleRate = 8000
        override val channels = 1
        var accepted = 0
        var largestWrite = 0
        var ended = false
        var failWaitAt = Int.MAX_VALUE
        var waits = 0
        var cancelAt = Int.MAX_VALUE

        override fun write(
            bytes: ByteArray,
            offset: Int,
        ): Int = write(bytes, offset, bytes.size - offset)

        override fun write(
            bytes: ByteArray,
            offset: Int,
            byteCount: Int,
        ): Int {
            largestWrite = maxOf(largestWrite, byteCount)
            accepted += byteCount
            return byteCount
        }

        override fun prepareForWait(endOfStream: Boolean): Long {
            if (++waits == cancelAt) throw kotlinx.coroutines.CancellationException("Retired render generation")
            if (waits == failWaitAt) throw RawOutputFailure("Device refused clock progress")
            ended = endOfStream
            return 1000
        }

        override fun positionUs() = accepted.toLong() * 1_000_000 / 16000

        override fun writtenPositionUs() = positionUs()

        override fun bufferedPositionUs() = positionUs()

        override fun drained() = ended

        override fun play() = Unit

        override fun pause() = Unit

        override fun volume(value: Float) = Unit

        override fun speed(value: Float) = Unit

        override fun close() = Unit
    }
}
