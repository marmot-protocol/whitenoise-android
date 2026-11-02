package dev.ipf.whitenoise.android.ui.conversation.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** What the platform retriever reports for one video file. */
internal data class RetrievedVideo(
    val location: String?,
    val date: String?,
    val duration: String?,
    val rotation: String?,
    val size: String,
    val decodedFrame: Boolean,
)

/** Every audio and video sample of one file, as a count and one digest over track, time, flags and bytes. */
internal data class SampleSummary(
    val count: Int,
    val digest: String,
)

/**
 * File-level evidence for the #3376 emulator fixture: digests, private strings, what the platform retriever reads and
 * the media samples Android's extractor returns. Every reader streams or works on the base movie, so a video padded
 * past the in-memory limit costs no more heap than a small one.
 */
internal object VideoMetadataFixtureEvidence {
    private const val CHUNK_BYTES = 1024 * 1024
    private const val SAMPLE_BUFFER_BYTES = 1024 * 1024
    private const val HEX_BYTE_MASK = 0xff

    /** SHA-256 of [file] as lowercase hex, streamed. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)
        file.inputStream().use { input ->
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * The [needles] that occur anywhere in [file] as ASCII, found in one bounded pass. Each chunk keeps the tail of
     * the one before it, so a string split across two reads is still found.
     */
    fun presentStrings(
        file: File,
        needles: Collection<String>,
    ): Set<String> {
        val targets = needles.associateWith { it.toByteArray(Charsets.US_ASCII) }
        val overlap = (targets.values.maxOfOrNull { it.size } ?: 1) - 1
        val buffer = ByteArray(CHUNK_BYTES + overlap)
        val found = mutableSetOf<String>()
        file.inputStream().use { input ->
            var carried = 0
            var read = input.read(buffer, 0, CHUNK_BYTES)
            while (read >= 0) {
                val end = carried + read
                targets.forEach { (needle, bytes) ->
                    if (needle !in found && buffer.indexOf(bytes, end) >= 0) found += needle
                }
                carried = minOf(overlap, end)
                System.arraycopy(buffer, end - carried, buffer, 0, carried)
                read = input.read(buffer, carried, CHUNK_BYTES)
            }
        }
        return found
    }

    /** What the platform retriever reports for [file], including whether it decodes a first frame. */
    fun retrieve(file: File): RetrievedVideo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            RetrievedVideo(
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

    /** Whether a retriever [date] is absent or a zeroed MP4 time, read as 1904 or the Unix epoch. */
    fun isEmptyDate(date: String?): Boolean = date == null || date.startsWith("1904") || date.startsWith("1970")

    /** Every audio and video sample of [file], in track order, summarized for an exact comparison. */
    fun samples(file: File): SampleSummary {
        val extractor = MediaExtractor()
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).forEach { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    digest.update("$index:$mime".toByteArray(Charsets.US_ASCII))
                    count += digestTrack(extractor, index, digest)
                }
            }
        } finally {
            extractor.release()
        }
        return SampleSummary(count, digest.digest().toHex())
    }

    /** Feeds every sample of one selected track (time, flags and bytes) into [digest] and returns how many. */
    private fun digestTrack(
        extractor: MediaExtractor,
        index: Int,
        digest: MessageDigest,
    ): Int {
        (0 until extractor.trackCount).forEach(extractor::unselectTrack)
        extractor.selectTrack(index)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
        var samples = 0
        var size = extractor.readSampleData(buffer, 0)
        while (size >= 0) {
            val bytes = ByteArray(size).also { buffer.get(it, 0, size) }
            digest.update("${extractor.sampleTime}:${extractor.sampleFlags}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
            samples++
            extractor.advance()
            buffer.clear()
            size = extractor.readSampleData(buffer, 0)
        }
        return samples
    }

    /** The first index of the non-empty [target] within the first [end] bytes, or -1. */
    private fun ByteArray.indexOf(
        target: ByteArray,
        end: Int,
    ): Int {
        var found = -1
        var start = 0
        while (found < 0 && start <= end - target.size) {
            if (this[start] == target[0] && matchesAt(start, target)) found = start
            start++
        }
        return found
    }

    /** Whether [target] occurs at [start], whose first byte is already known to match. */
    private fun ByteArray.matchesAt(
        start: Int,
        target: ByteArray,
    ): Boolean {
        var index = 1
        while (index < target.size && this[start + index] == target[index]) index++
        return index == target.size
    }

    /** Lowercase hex. */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and HEX_BYTE_MASK) }
}

/** The closed facts each route reports to the host checker: sizes, digests, counts and booleans, never content. */
internal object VideoRouteFacts {
    /** Strings no received video may carry, whatever its source: fixture markers and the muxer's version key. */
    private val ALWAYS_PRIVATE = listOf("WNPrivate", ANDROID_VERSION_KEY)

    /**
     * What a received video kept and lost against its [original] source and the [expected] scrubbed bytes: digests,
     * private strings, location, recording date, duration, rotation, dimensions, a first frame and every sample.
     */
    fun accepted(
        route: VideoRoute,
        original: File,
        expected: File,
        received: File,
    ): JSONObject {
        val evidence = VideoMetadataFixtureEvidence
        val before = evidence.retrieve(original)
        val after = evidence.retrieve(received)
        val privateStrings = route.expect.privateStrings
        val receivedStrings = evidence.presentStrings(received, (privateStrings + ALWAYS_PRIVATE).distinct())
        val originalSamples = evidence.samples(original)
        val receivedSamples = evidence.samples(received)
        return base(route, original)
            .put("phase", "video-metadata-route")
            .put("expected_sha256", evidence.sha256(expected))
            .put("received_sha256", evidence.sha256(received))
            .put("received_bytes", received.length())
            .put("private_strings", privateStrings.size)
            .put("input_private_strings", evidence.presentStrings(original, privateStrings).size)
            .put("received_private_strings", receivedStrings.size)
            .put("location_positive", route.expect.locationPositive)
            .put("input_location", before.location != null)
            .put("received_location", after.location != null)
            .put("received_date_empty", evidence.isEmptyDate(after.date))
            .put("duration_kept", before.duration != null && before.duration == after.duration)
            .put("rotation_kept", before.rotation == after.rotation)
            .put("dimensions_kept", before.size == after.size)
            .put("frame_decoded", after.decodedFrame)
            .put("samples_kept", originalSamples == receivedSamples)
            .put("sample_count", receivedSamples.count)
    }

    /** The source facts of a route the sender must refuse. */
    fun refused(
        route: VideoRoute,
        original: File,
    ): JSONObject = base(route, original).put("phase", "video-metadata-refusal")

    /** The route's name, entry, reported type, and the size and digest of the source the provider served. */
    private fun base(
        route: VideoRoute,
        original: File,
    ): JSONObject =
        JSONObject()
            .put("route", route.name)
            .put("entry", route.entry.label)
            .put("reported_mime", route.source.mimeType)
            .put("input_bytes", original.length())
            .put("input_sha256", VideoMetadataFixtureEvidence.sha256(original))
}
