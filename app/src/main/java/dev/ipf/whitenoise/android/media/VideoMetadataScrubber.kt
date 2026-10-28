package dev.ipf.whitenoise.android.media

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Container families the video metadata scrubber understands. */
internal enum class VideoContainer {
    IsoBmff,
    Matroska,
}

/** What one scrub removed, for diagnostics and tests. It never carries the removed values. */
internal data class VideoScrubSummary(
    val container: VideoContainer,
    val neutralizedElements: Int,
    val zeroedFields: Int,
    val zeroedSampleBytes: Long,
)

/** Outcome of removing private metadata from one local video. */
internal sealed interface VideoScrubResult {
    /** Every private field the policy names is gone; the file is still the same size. */
    data class Scrubbed(
        val summary: VideoScrubSummary,
    ) : VideoScrubResult

    /** A container this scrubber does not handle, so its metadata cannot be removed. */
    data object Unsupported : VideoScrubResult

    /** The structure is invalid or could not be read, so its metadata cannot be removed safely. */
    data object Malformed : VideoScrubResult
}

/** Raised inside a walker to end the scrub with a refusal; it never escapes [VideoMetadataScrubber]. */
internal class VideoScrubRefusal(
    val result: VideoScrubResult,
) : Exception() {
    /** A refusal is ordinary control flow for hostile input, so it skips the stack capture. */
    override fun fillInStackTrace(): Throwable = this
}

/** Ends the current scrub as [VideoScrubResult.Malformed]. */
internal fun malformedVideo(): Nothing = throw VideoScrubRefusal(VideoScrubResult.Malformed)

/** Ends the current scrub as [VideoScrubResult.Unsupported]. */
internal fun unsupportedVideo(): Nothing = throw VideoScrubRefusal(VideoScrubResult.Unsupported)

/**
 * Removes private container metadata from a local video before it is accepted for upload (#3376).
 *
 * The scrub rewrites the container in place and never re-encodes or remuxes: a platform muxer would stamp its own
 * version, device and time fields back into the file. Removed fields are overwritten with zeros, and whole metadata
 * boxes or elements become padding of exactly the same length, so the output has the input's size, every offset and
 * index stays valid, the audio and video sample bytes are never touched, and a second scrub changes nothing.
 *
 * ISO BMFF and QuickTime (MP4, MOV, M4V, 3GP, 3G2): `udta`, `meta`, `uuid` and `prft` boxes in the movie, track,
 * media and fragment structure, and every top-level box that is not part of the movie layout, become `free` boxes
 * with zeroed payloads; existing `free`, `skip` and `wide` payloads are zeroed; `mvhd`, `tkhd` and `mdhd` creation
 * and modification times are zeroed; handler names and video compressor names and vendors are zeroed; and every
 * sample of a timed-metadata track (any handler other than video, audio, auxiliary video, picture, subtitle and
 * text, for example location, timecode, camera motion or hint tracks) is zeroed inside its `mdat`. A fragmented file
 * with such a track is unsupported, and so is a track whose samples live in another file.
 *
 * Matroska and WebM: `Tags`, `Attachments`, `Chapters`, unknown segment-level elements and `CRC-32` elements of the
 * rewritten masters become `Void` elements of the same length; segment and neighbouring file names, the recording
 * date, the title and track and codec names are voided; the muxing and writing application strings are zeroed.
 *
 * Kept: track structure, timing, edit lists, rotation matrices, sample descriptions, codec configuration, language,
 * random segment identifiers, seek and cue indexes, and subtitle and text tracks as user-visible content. Not
 * covered: data inside the coded audio or video bitstreams (for example H.264 or HEVC SEI user data and encoder
 * banners), telemetry some cameras write into subtitle tracks, the file name, and what the codec choice reveals.
 * Forwarding an existing message reuses its uploaded reference and is not re-scrubbed.
 *
 * Content decides the container, never the MIME type. [VideoScrubResult.Unsupported] and
 * [VideoScrubResult.Malformed] may leave the input partly rewritten; the caller must discard it and must never send
 * the original bytes instead. Every size and offset is checked against the input, nesting depth and element count are
 * bounded, and no allocation depends on a size read from the input.
 */
internal object VideoMetadataScrubber {
    // EBML magic that opens every Matroska and WebM file.
    private const val EBML_MAGIC = 0x1A45DFA3L

    /** Scrubs [file] in place. A file that cannot be opened for writing reads as [VideoScrubResult.Malformed]. */
    fun scrub(file: File): VideoScrubResult =
        try {
            RandomAccessFile(file, "rw").use { scrub(FileScrubStore(it)) }
        } catch (_: IOException) {
            VideoScrubResult.Malformed
        }

    /** Scrubs [bytes] in place; callers own the array and discard it unless the result is scrubbed. */
    fun scrub(bytes: ByteArray): VideoScrubResult = scrub(ArrayScrubStore(bytes))

    /**
     * Returns scrubbed bytes without mutating [bytes]: the same array when it was already clean, otherwise a scrubbed
     * copy. Null when the metadata cannot be removed. Used for bytes a caller does not own, such as restored drafts.
     */
    fun scrubbedOrNull(bytes: ByteArray): ByteArray? {
        val store = CopyOnWriteScrubStore(bytes)
        return if (scrub(store) is VideoScrubResult.Scrubbed) store.bytes else null
    }

    /** Detects the container by content and runs its walker over [store]. */
    internal fun scrub(store: ScrubStore): VideoScrubResult =
        try {
            val reader = ScrubReader(store)
            when {
                store.size >= Int.SIZE_BYTES && reader.u32(0L) == EBML_MAGIC ->
                    VideoScrubResult.Scrubbed(MatroskaMetadataScrubber(reader).scrub())
                IsoBmffMetadataScrubber.looksLikeIsoBmff(reader) ->
                    VideoScrubResult.Scrubbed(IsoBmffMetadataScrubber(reader).scrub())
                else -> VideoScrubResult.Unsupported
            }
        } catch (refusal: VideoScrubRefusal) {
            refusal.result
        } catch (_: IOException) {
            VideoScrubResult.Malformed
        }
}
