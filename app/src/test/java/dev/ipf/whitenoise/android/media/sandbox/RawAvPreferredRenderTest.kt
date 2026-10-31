package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    @Test fun longGopPrerollDrainsTheActualBoundedReaderAndRetainsCrossingPcmOnce() =
        runBlocking {
            val legacy = PrerollPackets()
            val exhausted =
                assertThrows(IOException::class.java) {
                    repeat(350) { (legacy.read(AvStreamProtocol.VIDEO_TRACK) as AvStreamFrame.Video).bitmap.recycle() }
                }
            assertEquals("compressed_lookahead_budget_refused", exhausted.message)

            val owner = Owner().apply { targetUs = 14_010_000L }
            val device = RawPcmOutputSonicTest.Device()
            val renderer =
                RawAvRenderSession(
                    ApplicationProvider.getApplicationContext(),
                    Uri.EMPTY,
                    true,
                    true,
                    owner,
                ) { rate, channels, _ -> RawPcmOutput(rate, channels, device) }
            renderer.speed(.25f)
            val packets = PrerollPackets()
            renderer.consumeFrames(AvStreamInfo(2, 2, 14_400_000, 0, 8000, 1), 7, packets::read, {
                assertEquals(owner.targetUs, it)
            })
            assertTrue(owner.ended)
            assertEquals((351..359).map { it * 40_000L }, owner.displayed)
            assertTrue("Interleaved audio must be consumed during preroll", packets.audioReads > 512)
            assertTrue(packets.maximumQueued <= 512)
            assertEquals(1, packets.crossingReads)
            // The real quarter-speed processor receives the clipped390ms exactly once, including its10ms suffix.
            assertEquals(24_960, device.audio.size())
            assertEquals(14_400_000L, renderer.positionUs())
            renderer.pause()
        }

    @Test fun revokedVideoPrerollNeverStartsAnotherAudioRead() =
        runBlocking {
            for (replaceGeneration in listOf(false, true)) {
                val owner = Owner().apply { targetUs = 100_000 }
                val renderer = session(owner, Sink())
                val frame = video(0)
                var reads = 0
                renderer.consumeFrames(info(), 7, { track ->
                    assertEquals(AvStreamProtocol.VIDEO_TRACK, track)
                    assertEquals(1, ++reads)
                    if (replaceGeneration) owner.activation = 8 else owner.alive = false
                    frame
                }, {})
                assertTrue(frame.bitmap.isRecycled)
                assertEquals(1, reads)
                assertTrue(owner.displayed.isEmpty())
                assertFalse(owner.ended)
            }
        }

    @Test fun revokedPrerollAudioReplyCannotAdoptItsCrossingSuffixOrReadAgain() =
        runBlocking {
            val owner = Owner().apply { targetUs = 100_000 }
            var deviceCreations = 0
            val renderer =
                RawAvRenderSession(
                    ApplicationProvider.getApplicationContext(),
                    Uri.EMPTY,
                    true,
                    true,
                    owner,
                ) { _, _, _ ->
                    deviceCreations++
                    Sink()
                }
            var reads = 0
            val frame = video(0)
            renderer.consumeFrames(info(), 7, { track ->
                when (++reads) {
                    1 -> {
                        assertEquals(AvStreamProtocol.VIDEO_TRACK, track)
                        frame
                    }
                    2 -> {
                        assertEquals(AvStreamProtocol.AUDIO_TRACK, track)
                        owner.alive = false
                        AvStreamFrame.Audio(ByteArray(320), 8000, 1, 90_000)
                    }
                    else -> error("A revoked generation must not issue another read")
                }
            }, {})
            assertEquals(2, reads)
            assertTrue(frame.bitmap.isRecycled)
            assertEquals(0, deviceCreations)
            assertTrue(owner.displayed.isEmpty())
        }

    @Test fun revokedSeekAcknowledgmentCannotStartPrerollReads() =
        runBlocking {
            val owner = Owner().apply { targetUs = 100_000 }
            session(owner, Sink()).consumeFrames(info(), 7, { error("No read after seek revocation") }, {
                owner.alive = false
            })
            assertFalse(owner.ended)
        }

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
        var alive = true
        var activation = 7L
        var throwAfterAdoption = false

        override fun intent(): RawAvPlaybackIntent {
            val attributes = AudioAttributes.Builder().build()
            return RawAvPlaybackIntent(alive, true, activation, 1, targetUs, 1f, 1f, attributes)
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

    /** Same finite demux/selected-track queue shape and aggregate packet cap as the native preferred reader. */
    private class PrerollPackets {
        private data class Packet(
            val audio: Boolean,
            val timeUs: Long,
        )

        private val packets =
            buildList {
                repeat(360) { index ->
                    add(Packet(true, index * 40_000L))
                    add(Packet(false, index * 40_000L))
                    add(Packet(true, index * 40_000L + 20_000))
                }
            }
        private var cursor = 0
        private val audio = ArrayDeque<Long>()
        private val video = ArrayDeque<Long>()
        var audioReads = 0
        var crossingReads = 0
        var maximumQueued = 0

        fun read(track: Int): AvStreamFrame {
            val selected = if (track == AvStreamProtocol.AUDIO_TRACK) audio else video
            while (selected.isEmpty() && cursor < packets.size) {
                if (audio.size + video.size >= 512) throw IOException("compressed_lookahead_budget_refused")
                val packet = packets[cursor++]
                (if (packet.audio) audio else video).addLast(packet.timeUs)
                maximumQueued = maxOf(maximumQueued, audio.size + video.size)
            }
            val time = selected.removeFirstOrNull()
            if (time == null) {
                return if (track == AvStreamProtocol.AUDIO_TRACK) AvStreamFrame.AudioEnd else AvStreamFrame.VideoEnd
            }
            return if (track == AvStreamProtocol.AUDIO_TRACK) {
                audioReads++
                if (time == 14_000_000L) crossingReads++
                AvStreamFrame.Audio(ByteArray(320) { if (it % 2 == 1) 32 else 0 }, 8000, 1, time)
            } else {
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(0xff000000.toInt() or time.toInt())
                AvStreamFrame.Video(bitmap, time)
            }
        }
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
