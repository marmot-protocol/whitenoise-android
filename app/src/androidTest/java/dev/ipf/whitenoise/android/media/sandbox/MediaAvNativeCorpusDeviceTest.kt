package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Color
import android.os.Process
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Tiny synthetic common-format checks; real camera/HDR/performance qualification remains separate. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
class MediaAvNativeCorpusDeviceTest {
    /** Common Video Formats Return Red Pixels From Fresh Restricted Workers. */
    @Test
    fun commonVideoFormatsReturnRedPixelsFromFreshRestrictedWorkers() {
        for (name in listOf("red-h264.mp4", "red-vp9.webm", "red-av1.webm", "red-hevc.mp4")) {
            withFixture(name) { source ->
                val previous = IsolatedMediaDecoder.lastAvIsolation
                val probe = requireNotNull(IsolatedMediaDecoder.probe(source, 64, true)) { name }
                try {
                    restricted(previous)
                    assertTrue("Duration: $name", probe.durationMs in 800L..1500L)
                    val poster = requireNotNull(probe.poster) { name }
                    assertEquals(64, poster.width)
                    assertEquals(48, poster.height)
                    val pixel = poster.getPixel(32, 24)
                    assertTrue("Red channel: $name", Color.red(pixel) > 200)
                    assertTrue("Green channel: $name", Color.green(pixel) < 50)
                    assertTrue("Blue channel: $name", Color.blue(pixel) < 50)
                } finally {
                    probe.poster?.recycle()
                }
            }
        }
    }

    /** Common Audio Formats Return Finite Waveforms And Durations. */
    @Test
    fun commonAudioFormatsReturnFiniteWaveformsAndDurations() {
        for (name in listOf("tone-aac.m4a", "tone-opus.ogg", "tone-mp3.mp3", "tone-pcm.wav")) {
            withFixture(name) { source ->
                val beforeWaveform = IsolatedMediaDecoder.lastAvIsolation
                val waveform = requireNotNull(IsolatedMediaDecoder.waveform(source)) { name }
                restricted(beforeWaveform)
                assertEquals(64, waveform.size)
                assertTrue("Waveform range: $name", waveform.all { it.isFinite() && it in 0f..1f })
                assertTrue("Waveform signal: $name", waveform.any { it > 0.05f })
                val beforeProbe = IsolatedMediaDecoder.lastAvIsolation
                val probe = requireNotNull(IsolatedMediaDecoder.probe(source, 32, false)) { name }
                try {
                    restricted(beforeProbe)
                    assertTrue("Duration: $name", probe.durationMs in 800L..1500L)
                } finally {
                    probe.poster?.recycle()
                }
            }
        }
    }

    /** Preferred video reads tolerate interleaved AAC while an unsupported default audio track is skipped. */
    @Test
    fun sparseVideoStartsAndPlaysItsSupportedAlternativeAudioTrack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withFixture("sparse-h264-aac-with-default-ac3.mp4") { source ->
            val stream = IsolatedAvStream.openUri(context, android.net.Uri.fromFile(source), edge = 64)
            stream.use {
                val first = stream.read(AvStreamProtocol.VIDEO_TRACK)
                assertTrue(first is AvStreamFrame.Video)
                (first as AvStreamFrame.Video).bitmap.recycle()
                assertTrue(stream.read(AvStreamProtocol.AUDIO_TRACK) is AvStreamFrame.Audio)
            }
            assertTrue(stream.awaitRetired())
        }
    }

    /** Preferred reads keep the frame covering an interior seek or the final display interval. */
    @Test
    fun preferredReadPreservesCoveringFrameAfterSeek() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withFixture("red-h264.mp4") { source ->
            val stream =
                IsolatedAvStream.openUri(context, android.net.Uri.fromFile(source), wantAudio = false, edge = 64)
            stream.use {
                assertVideoTimestamp(stream, 0L)
                stream.seek(900_000L)
                assertVideoTimestamp(stream, 800_000L)
                assertEquals(AvStreamFrame.VideoEnd, stream.read(AvStreamProtocol.VIDEO_TRACK))
                stream.seek(200_000L)
                assertVideoTimestamp(stream, 200_000L)
                stream.seek(300_000L)
                assertVideoTimestamp(stream, 200_000L)
                assertVideoTimestamp(stream, 400_000L)
            }
            assertTrue(stream.awaitRetired())
        }
    }

    /** Paused probes show the frame already covering an interior gap, an exact frame or the video tail. */
    @Test
    fun pausedProbeReturnsPixelsThatCoverTheSeekTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withFixture("sparse-color-h264.mp4") { source ->
            val uri = android.net.Uri.fromFile(source)
            for ((positionMs, coveringGreen) in listOf(7_000L to true, 10_000L to false, 12_000L to false)) {
                val probe = IsolatedMediaDecoder.probeUriAt(context.contentResolver, uri, positionMs, edge = 64)
                val poster = requireNotNull(probe?.poster) { "Missing poster at ${positionMs}ms" }
                try {
                    val pixel = poster.getPixel(32, 24)
                    assertTrue("Unexpected color at ${positionMs}ms", Color.red(pixel) < 50)
                    assertTrue(
                        "Wrong frame at ${positionMs}ms",
                        if (coveringGreen) {
                            Color.green(pixel) > 200 && Color.blue(pixel) < 50
                        } else {
                            Color.blue(pixel) > 200 && Color.green(pixel) < 50
                        },
                    )
                } finally {
                    poster.recycle()
                }
            }
        }
    }

    private fun assertVideoTimestamp(
        stream: IsolatedAvStream,
        expectedUs: Long,
    ) {
        val frame = stream.read(AvStreamProtocol.VIDEO_TRACK) as AvStreamFrame.Video
        try {
            assertEquals(expectedUs, frame.timestampUs)
        } finally {
            frame.bitmap.recycle()
        }
    }

    /** Silent Audio Returns No Waveform From Fresh Restricted Worker. */
    @Test
    fun silentAudioReturnsNoWaveformFromFreshRestrictedWorker() {
        // This unchanged video contains decoded silent AAC; silence has no normalized waveform.
        // Use one fresh path once: repeated null results can come from the refusal cache.
        withFixture("original-video.mp4", verifiedVideoFixture()) { source ->
            val previous = IsolatedMediaDecoder.lastAvIsolation
            assertNull(IsolatedMediaDecoder.waveform(source))
            restricted(previous)
        }
    }

    /** Copies and closes one pinned corpus fixture around the selected device assertion. */
    private fun withFixture(
        name: String,
        verifiedBytes: ByteArray? = null,
        checkOutput: (File) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        IsolatedMediaDecoder.install(context)
        val source = File.createTempFile("native-corpus-", name.substringAfterLast('.'), context.cacheDir)
        val canary = File(context.filesDir, "media-containment-canary")
        var ownsCanary = false
        try {
            check(canary.createNewFile()) { "Another fixture owns the private canary" }
            ownsCanary = true
            canary.writeText("synthetic-native-corpus-canary")
            if (verifiedBytes == null) {
                instrumentation.context.assets.open("native-av-corpus/$name").use { input ->
                    source.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                source.writeBytes(verifiedBytes)
            }
            checkOutput(source)
        } finally {
            val sourceDeleted = source.delete()
            val canaryDeleted = !ownsCanary || canary.delete()
            check(sourceDeleted && canaryDeleted) { "Synthetic fixture cleanup failed" }
        }
    }

    /** Checks the observed worker identity and capability restrictions for this exact corpus operation. */
    private fun restricted(previous: WorkerIsolation?) {
        val worker = requireNotNull(IsolatedMediaDecoder.lastAvIsolation)
        assertNotSame(previous, worker)
        assertNotEquals(Process.myUid(), worker.uid)
        assertTrue(worker.isolated)
        assertFalse(worker.internetGranted)
        assertFalse(worker.runtimeInitialized)
        assertTrue(worker.privateCanaryDenied)
        assertEquals(OsConstants.EBADF, worker.inputWriteErrno)
    }
}
