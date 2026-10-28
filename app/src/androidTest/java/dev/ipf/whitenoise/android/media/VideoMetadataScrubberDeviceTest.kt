package dev.ipf.whitenoise.android.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/**
 * Platform proof for #3376 on a real device or emulator: every tagged fixture, scrubbed in place, loses its location
 * and recording date in Android's own retriever while its frames, duration, rotation and every audio and video sample
 * (bytes, timestamps and flags) through Android's own extractor stay identical to the original.
 */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class VideoMetadataScrubberDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var directory: File

    /** Creates a private working directory for the original and scrubbed copies. */
    @Before
    fun setUp() {
        directory = File(instrumentation.targetContext.cacheDir, "video-metadata-device-test").apply { mkdirs() }
    }

    /** Deletes every copy this test wrote. */
    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** The platform retriever finds no location or recording date after scrubbing, and playback metadata is kept. */
    @Test
    fun scrubbedFixturesLoseLocationAndDateAndKeepPlaybackMetadata() {
        var locatedBefore = 0
        FIXTURES.forEach { name ->
            val (original, scrubbed) = copies(name)
            val before = retrieve(original)
            val after = retrieve(scrubbed)
            if (before.location != null) locatedBefore += 1
            assertNull("$name location", after.location)
            val date = after.date
            val noDate = date == null || date.startsWith("1904") || date.startsWith("1970")
            assertTrue("$name date $date", noDate)
            assertEquals("$name duration", before.duration, after.duration)
            assertEquals("$name rotation", before.rotation, after.rotation)
            assertEquals("$name size", before.size, after.size)
            assertTrue("$name must decode a frame", after.decodedFrame)
        }
        assertTrue("at least one fixture must carry a location the platform reads", locatedBefore > 0)
        val rotation = retrieve(copies(MOV).second).rotation
        assertTrue("the rotated QuickTime fixture keeps its matrix, got $rotation", rotation in setOf("90", "270"))
    }

    /** Every audio and video sample keeps its bytes, timestamp and flags through Android's extractor. */
    @Test
    fun scrubbedFixturesKeepEveryMediaSample() {
        FIXTURES.forEach { name ->
            val (original, scrubbed) = copies(name)
            val before = mediaSamples(original)
            val after = mediaSamples(scrubbed)
            assertTrue("$name must expose media tracks", before.isNotEmpty())
            assertEquals("$name track formats", before.keys, after.keys)
            before.forEach { (track, samples) ->
                assertTrue("$name $track must have samples", samples.isNotEmpty())
                assertEquals("$name $track samples", samples, after.getValue(track))
            }
        }
    }

    /** Writes the original fixture and a scrubbed copy, checking the scrub succeeded and kept the size. */
    private fun copies(name: String): Pair<File, File> {
        val encoded =
            instrumentation.context.assets
                .open("video-metadata/$name.b64")
                .use { it.readBytes() }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val original = File(directory, "original-$name").apply { writeBytes(bytes) }
        val scrubbed = File(directory, "scrubbed-$name").apply { writeBytes(bytes) }
        val result = VideoMetadataScrubber.scrub(scrubbed)
        assertTrue("$name: $result", result is VideoScrubResult.Scrubbed)
        assertEquals(bytes.size.toLong(), scrubbed.length())
        return original to scrubbed
    }

    /** What the platform retriever reports for [file]. */
    private fun retrieve(file: File): Retrieved {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            Retrieved(
                location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION),
                date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                size =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) + "x" +
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                decodedFrame = frame != null,
            ).also { frame?.recycle() }
        } finally {
            retriever.release()
        }
    }

    /** Every audio and video sample of [file], keyed by track index and MIME type. */
    private fun mediaSamples(file: File): Map<String, List<Sample>> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val tracks =
                (0 until extractor.trackCount).mapNotNull { index ->
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                    ("$index:$mime" to index).takeIf { mime.startsWith("video/") || mime.startsWith("audio/") }
                }
            return tracks.associate { (key, index) -> key to readTrack(extractor, index) }
        } finally {
            extractor.release()
        }
    }

    /** Reads every sample of one selected track from the start. */
    private fun readTrack(
        extractor: MediaExtractor,
        index: Int,
    ): List<Sample> {
        (0 until extractor.trackCount).forEach(extractor::unselectTrack)
        extractor.selectTrack(index)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
        val samples = mutableListOf<Sample>()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val bytes = ByteArray(size).also { buffer.get(it, 0, size) }
            samples += Sample(extractor.sampleTime, extractor.sampleFlags, bytes.toList())
            extractor.advance()
        }
        return samples
    }

    /** The retriever fields this test compares. */
    private data class Retrieved(
        val location: String?,
        val date: String?,
        val duration: String?,
        val rotation: String?,
        val size: String,
        val decodedFrame: Boolean,
    )

    /** One extracted sample. */
    private data class Sample(
        val timeUs: Long,
        val flags: Int,
        val bytes: List<Byte>,
    )

    private companion object {
        const val MOV = "mov-quicktime-keys.mov"
        const val SAMPLE_BUFFER_BYTES = 1024 * 1024
        val FIXTURES = listOf("mp4-tagged.mp4", MOV, "mp4-timecode.mp4", "mkv-tagged.mkv", "webm-live-tagged.webm")
    }
}
