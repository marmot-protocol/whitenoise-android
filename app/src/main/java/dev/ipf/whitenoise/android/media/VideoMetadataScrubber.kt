package dev.ipf.whitenoise.android.media

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Container families the video metadata scrubber understands. */
internal enum class VideoContainer {
    IsoBmff,
    Matroska,
}

/**
 * What one scrub removed, for diagnostics and tests, and how many video tracks the container declares. It never
 * carries the removed values.
 */
internal data class VideoScrubSummary(
    val container: VideoContainer,
    val neutralizedElements: Int,
    val zeroedFields: Int,
    val zeroedSampleBytes: Long,
    val videoTracks: Int,
)

/** Outcome of removing private metadata from one local video. */
internal sealed interface VideoScrubResult {
    /** Every private field the policy names is gone, and the file is still the same size. */
    data class Scrubbed(
        val summary: VideoScrubSummary,
    ) : VideoScrubResult

    /** A container this scrubber does not handle, so its metadata cannot be removed. */
    data object Unsupported : VideoScrubResult

    /** The structure is invalid or could not be read, so its metadata cannot be removed safely. */
    data object Malformed : VideoScrubResult
}

/** Raised inside a walker to end the scrub with a refusal. It never escapes [VideoMetadataScrubber]. */
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
 * The scrub rewrites the container in place and never re-encodes or remuxes, because a platform muxer would stamp its
 * own version, device and time fields back into the file. Removed fields are overwritten with zeros, and whole
 * metadata boxes or elements become padding of exactly the same length. The output therefore has the input's size,
 * every offset and index stays valid, the audio and video sample bytes are never touched, and a second scrub changes
 * nothing.
 *
 * ISO BMFF and QuickTime (MP4, MOV, M4V, 3GP, 3G2):
 * - `udta`, `meta`, `uuid` and `prft` boxes in the movie, track, media and fragment structure, and every top-level
 *   box that is not part of the movie layout, become `free` boxes with zeroed payloads.
 * - Existing `free`, `skip` and `wide` payloads are zeroed.
 * - `mvhd`, `tkhd` and `mdhd` creation and modification times are zeroed.
 * - Everything after each handler's type is zeroed: the QuickTime component manufacturer, flags and mask, and the
 *   handler name.
 * - The vendor of every audio and video sample entry, and each video entry's compressor name, are zeroed.
 * - Every sample of a timed-metadata track (any handler other than video, audio, auxiliary video, picture, subtitle
 *   and text, for example location, timecode, camera motion or hint tracks) is zeroed inside its `mdat`.
 *
 * A fragmented file with such a track is unsupported, and so is a track whose samples live in another file. Bytes
 * after the last top-level box are accepted only when they are all zero, so a recording with a non-box trailer is
 * refused.
 *
 * Matroska and WebM: `Tags`, `Attachments`, `Chapters`, unknown segment-level elements and the `CRC-32` elements of
 * the rewritten masters become `Void` elements of the same length. Segment and neighbouring file names, the
 * recording date, the title and track and codec names are voided, and the muxing and writing application strings
 * are zeroed.
 *
 * Kept: track structure, timing, edit lists, rotation matrices, sample descriptions other than their vendor and
 * compressor name, codec configuration, language, random segment identifiers, seek and cue indexes, and subtitle and
 * text tracks as user-visible content. Not covered: data inside the coded audio or video bitstreams (for example H.264
 * or HEVC SEI user data and encoder banners), telemetry some cameras write into subtitle tracks, the file name, and
 * what the codec choice reveals. Forwarding an existing message re-uploads its original plaintext unchanged.
 *
 * Content decides the container, never the MIME type. [classify] runs the same walk over a read-only view, so the
 * Files picker can decide by content whether a pick is a video. [VideoScrubResult.Unsupported] and
 * [VideoScrubResult.Malformed] may leave the input partly rewritten, so the caller must discard it and must never
 * send the original bytes instead. Every size and offset is checked against the input, nesting depth and element
 * count are bounded, and no allocation depends on a size read from the input.
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

    /** Scrubs [bytes] in place. Callers own the array and discard it unless the result is scrubbed. */
    fun scrub(bytes: ByteArray): VideoScrubResult = scrub(ArrayScrubStore(bytes))

    /**
     * Returns scrubbed bytes without mutating [bytes]: the same array when it was already clean, otherwise a scrubbed
     * copy. Null when the metadata cannot be removed. Used for bytes a caller does not own, such as restored drafts.
     */
    fun scrubbedOrNull(bytes: ByteArray): ByteArray? {
        val store = CopyOnWriteScrubStore(bytes)
        return if (scrub(store) is VideoScrubResult.Scrubbed) store.bytes else null
    }

    /**
     * Classifies [file] by content without changing it, by running the scrubber's own walk over a read-only view, so
     * classification and scrubbing agree. An unreadable file reads as [VideoContent.Other].
     */
    fun classify(file: File): VideoContent =
        try {
            RandomAccessFile(file, "r").use { classify(FileScrubStore(it)) }
        } catch (_: IOException) {
            VideoContent.Other
        }

    /** Classifies [bytes] by content without changing them. */
    fun classify(bytes: ByteArray): VideoContent = classify(ArrayScrubStore(bytes))

    /**
     * Runs the container's walk over a read-only view of [store] and reports what its content proves. A walk that
     * refuses part-way still reports the video tracks it met before the refusal, and ISO BMFF must also carry a video
     * brand, so a still image such as a Canon CR3 raw photo is never taken for a video.
     */
    internal fun classify(store: ScrubStore): VideoContent {
        val reader = ScrubReader(ReadOnlyScrubStore(store))
        return when {
            isEbml(reader) -> containerContent(MatroskaMetadataScrubber(reader), IsoBrand.Video)
            IsoBmffMetadataScrubber.looksLikeIsoBmff(reader) ->
                containerContent(IsoBmffMetadataScrubber(reader), IsoBrands.classify(reader))
            VideoContainerSignatures.matches(reader) -> VideoContent.UnsupportedContainer
            else -> VideoContent.Other
        }
    }

    /** Detects the container by content and runs its walker over [store]. */
    internal fun scrub(store: ScrubStore): VideoScrubResult =
        try {
            val reader = ScrubReader(store)
            when {
                isEbml(reader) -> VideoScrubResult.Scrubbed(MatroskaMetadataScrubber(reader).scrub())
                IsoBmffMetadataScrubber.looksLikeIsoBmff(reader) ->
                    VideoScrubResult.Scrubbed(IsoBmffMetadataScrubber(reader).scrub())
                else -> VideoScrubResult.Unsupported
            }
        } catch (refusal: VideoScrubRefusal) {
            refusal.result
        } catch (_: IOException) {
            VideoScrubResult.Malformed
        }

    /** Whether [reader] starts with the EBML magic of Matroska and WebM. */
    private fun isEbml(reader: ScrubReader): Boolean = reader.size >= Int.SIZE_BYTES && reader.u32(0L) == EBML_MAGIC

    /**
     * Runs [walk] for classification and decides in this order:
     * - Input whose very first box or element does not parse is not a container at all, so weak magic in text stays
     *   text.
     * - A still-image [brand] is a document whatever the walk found, because a well-formed one is sent unchanged too.
     * - A video [brand] whose walk met a video track is a video, even if a later part, such as a trailer, was refused.
     * - A recognised container whose walk was refused as malformed, or before it could read the track list, is
     *   unreadable, because a failed walk proves nothing about video. The one exception is a refusal after the walk
     *   read the complete track list and found no video track, such as an ID3 tag after an `.m4a` movie: that file
     *   is audio-only.
     * - Anything else, such as an audio-only file or a HEIF still without a movie, is unconfirmed.
     */
    private fun containerContent(
        walk: ContainerWalk,
        brand: IsoBrand,
    ): VideoContent {
        val refusal = walk.refusalOrNull()
        val audioOnly = walk.trackListRead && walk.videoTracks == 0
        val refused = refusal == VideoScrubResult.Malformed || (refusal != null && walk.trackListHidden)
        val unreadable = refused && !audioOnly
        return when {
            !walk.structureRecognised -> VideoContent.Other
            brand == IsoBrand.StillImage -> VideoContent.UnconfirmedContainer
            brand == IsoBrand.Video && walk.videoTracks > 0 -> VideoContent.VideoContainer
            unreadable -> VideoContent.UnreadableContainer
            else -> VideoContent.UnconfirmedContainer
        }
    }
}

/** One container's scrub walk, which counts the video tracks it meets as it goes. */
internal interface ContainerWalk {
    val videoTracks: Int

    /** Whether the walk parsed the first top-level box or element, so the input really is this container. */
    val structureRecognised: Boolean

    /** Whether a track list exists that a refusal stopped the walk from reading in full. */
    val trackListHidden: Boolean

    /**
     * Whether the walk read a complete track list (the ISO BMFF movie box, or the current Matroska Segment's Tracks)
     * before any refusal, so [videoTracks] counts every track that list declares.
     */
    val trackListRead: Boolean

    /** Walks and rewrites the whole container, or ends with a [VideoScrubRefusal]. */
    fun scrub(): VideoScrubSummary
}

/**
 * Runs this walk for classification only and returns how it ended: null when it completed, otherwise its refusal, with
 * a read failure reported as malformed. The video tracks it met before a refusal stay counted on the walk.
 */
private fun ContainerWalk.refusalOrNull(): VideoScrubResult? =
    try {
        scrub()
        null
    } catch (refusal: VideoScrubRefusal) {
        refusal.result
    } catch (_: IOException) {
        VideoScrubResult.Malformed
    }
