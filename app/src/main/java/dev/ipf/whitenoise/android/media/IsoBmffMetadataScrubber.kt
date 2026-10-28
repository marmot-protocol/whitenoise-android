package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.media.IsoBoxEditor.Companion.BOX_HEADER_BYTES
import dev.ipf.whitenoise.android.media.IsoBoxTypes.CMOV
import dev.ipf.whitenoise.android.media.IsoBoxTypes.CO64
import dev.ipf.whitenoise.android.media.IsoBoxTypes.DINF
import dev.ipf.whitenoise.android.media.IsoBoxTypes.DREF
import dev.ipf.whitenoise.android.media.IsoBoxTypes.FREE
import dev.ipf.whitenoise.android.media.IsoBoxTypes.FTYP
import dev.ipf.whitenoise.android.media.IsoBoxTypes.HDLR
import dev.ipf.whitenoise.android.media.IsoBoxTypes.MDAT
import dev.ipf.whitenoise.android.media.IsoBoxTypes.MDHD
import dev.ipf.whitenoise.android.media.IsoBoxTypes.MDIA
import dev.ipf.whitenoise.android.media.IsoBoxTypes.MINF
import dev.ipf.whitenoise.android.media.IsoBoxTypes.MOOF
import dev.ipf.whitenoise.android.media.IsoBoxTypes.MOOV
import dev.ipf.whitenoise.android.media.IsoBoxTypes.MVEX
import dev.ipf.whitenoise.android.media.IsoBoxTypes.MVHD
import dev.ipf.whitenoise.android.media.IsoBoxTypes.SKIP
import dev.ipf.whitenoise.android.media.IsoBoxTypes.STBL
import dev.ipf.whitenoise.android.media.IsoBoxTypes.STCO
import dev.ipf.whitenoise.android.media.IsoBoxTypes.STSC
import dev.ipf.whitenoise.android.media.IsoBoxTypes.STSD
import dev.ipf.whitenoise.android.media.IsoBoxTypes.STSZ
import dev.ipf.whitenoise.android.media.IsoBoxTypes.STZ2
import dev.ipf.whitenoise.android.media.IsoBoxTypes.TKHD
import dev.ipf.whitenoise.android.media.IsoBoxTypes.TRAF
import dev.ipf.whitenoise.android.media.IsoBoxTypes.TRAK
import dev.ipf.whitenoise.android.media.IsoBoxTypes.VIDE
import dev.ipf.whitenoise.android.media.IsoBoxTypes.WIDE

/** What the walk learned about one track; its samples are handled once the whole movie box has been read. */
internal class IsoTrackScan {
    var handler: Int = 0
    var sampleDescriptions: IsoBox? = null
    var sampleToChunk: IsoBox? = null
    var sampleSizes: IsoBox? = null
    var chunkOffsets: IsoBox? = null
    var externalData: Boolean = false
}

/** Nesting levels the walk descends into; every other box is kept or rewritten without being opened. */
private enum class IsoLevel {
    Movie,
    Track,
    Media,
    MediaInfo,
    DataInfo,
    SampleTable,
    MovieExtends,
    Fragment,
    TrackFragment,
}

/**
 * Scrubs one ISO BMFF or QuickTime file in place, following the policy documented on [VideoMetadataScrubber].
 * A first pass validates the top level and records the movie, media data and fragments; the second rewrites.
 */
internal class IsoBmffMetadataScrubber(
    private val reader: ScrubReader,
) {
    private val budget = ScrubBudget()
    private val editor = IsoBoxEditor(reader, budget)
    private val mediaData = MediaDataRanges()
    private val tracks = mutableListOf<IsoTrackScan>()
    private var currentTrack: IsoTrackScan? = null
    private var fragmented = false

    /** Validates, rewrites and summarizes the whole file. */
    fun scrub(): VideoScrubSummary {
        var movies = 0
        forEachTopLevelBox { box ->
            when (box.type) {
                MOOV -> movies += 1
                MDAT -> mediaData.add(box.payload, box.end)
                MOOF -> fragmented = true
            }
        }
        // Without a movie box this is not a video (a HEIF image, for example), and two make it ambiguous.
        if (movies == 0) unsupportedVideo()
        if (movies > 1) malformedVideo()
        forEachTopLevelBox(::scrubTopLevel)
        val samples = IsoSampleZeroing(reader, mediaData)
        var zeroedSampleBytes = 0L
        tracks.forEach { track -> zeroedSampleBytes += finishTrack(track, samples) }
        return VideoScrubSummary(VideoContainer.IsoBmff, editor.neutralized, editor.zeroedFields, zeroedSampleBytes)
    }

    /** Calls [action] for every top-level box, tolerating only zero padding after the last one. */
    private inline fun forEachTopLevelBox(action: (IsoBox) -> Unit) {
        var position = 0L
        while (position < reader.size) {
            val remaining = reader.size - position
            val padding = remaining < BOX_HEADER_BYTES || reader.unsigned(position, BOX_HEADER_BYTES) == 0L
            if (padding) {
                if (!reader.isZeroTail(position)) malformedVideo()
                break
            }
            val box = editor.readBox(position, reader.size, allowToEnd = true)
            action(box)
            position = box.end
        }
    }

    /** Keeps the movie layout, opens the movie and fragments, and neutralizes every other top-level box. */
    private fun scrubTopLevel(box: IsoBox) {
        when (box.type) {
            MOOV -> walk(box, IsoLevel.Movie, depth = 1)
            MOOF -> walk(box, IsoLevel.Fragment, depth = 1)
            FREE, SKIP, WIDE -> editor.zeroPayload(box)
            in KEPT_TOP_LEVEL -> Unit
            else -> editor.neutralize(box)
        }
    }

    /** Visits each child of [container]; QuickTime may end a child list with a few zero bytes. */
    private fun walk(
        container: IsoBox,
        level: IsoLevel,
        depth: Int,
    ) {
        budget.checkDepth(depth)
        var position = container.payload
        while (position < container.end) {
            if (container.end - position < BOX_HEADER_BYTES) {
                if (reader.unsigned(position, (container.end - position).toInt()) != 0L) malformedVideo()
                break
            }
            val child = editor.readBox(position, container.end, allowToEnd = false)
            when (child.type) {
                in NEUTRALIZED -> editor.neutralize(child)
                FREE, SKIP, WIDE -> editor.zeroPayload(child)
                else -> scrubStructural(child, level, depth)
            }
            position = child.end
        }
    }

    /** Applies the per-level rule for a child that is neither metadata nor padding. */
    private fun scrubStructural(
        child: IsoBox,
        level: IsoLevel,
        depth: Int,
    ) {
        val type = child.type
        when (level) {
            IsoLevel.Movie -> scrubMovieChild(child, depth)
            IsoLevel.Track ->
                when (type) {
                    TKHD -> editor.zeroTimes(child)
                    MDIA -> walk(child, IsoLevel.Media, depth + 1)
                }
            IsoLevel.Media -> scrubMediaChild(child, depth)
            IsoLevel.MediaInfo -> scrubMediaInfoChild(child, depth)
            IsoLevel.DataInfo ->
                if (type == DREF && !editor.dataReferencesSelfContained(child)) currentTrack?.externalData = true
            IsoLevel.SampleTable -> recordSampleTable(child)
            IsoLevel.Fragment -> if (type == TRAF) walk(child, IsoLevel.TrackFragment, depth + 1)
            IsoLevel.MovieExtends, IsoLevel.TrackFragment -> Unit
        }
    }

    /** Movie header times, tracks and fragment declarations; a compressed movie cannot be inspected. */
    private fun scrubMovieChild(
        child: IsoBox,
        depth: Int,
    ) {
        when (child.type) {
            MVHD -> editor.zeroTimes(child)
            TRAK -> {
                if (tracks.size >= MAX_TRACKS) malformedVideo()
                val track = IsoTrackScan().also(tracks::add)
                currentTrack = track
                walk(child, IsoLevel.Track, depth + 1)
                currentTrack = null
            }
            MVEX -> {
                fragmented = true
                walk(child, IsoLevel.MovieExtends, depth + 1)
            }
            CMOV -> unsupportedVideo()
        }
    }

    /** Media header times, the track's handler and its media information. */
    private fun scrubMediaChild(
        child: IsoBox,
        depth: Int,
    ) {
        when (child.type) {
            MDHD -> editor.zeroTimes(child)
            HDLR -> {
                val handler = editor.zeroHandlerName(child)
                currentTrack?.handler = handler
            }
            MINF -> walk(child, IsoLevel.MediaInfo, depth + 1)
        }
    }

    /** QuickTime's data handler name, the data references and the sample table. */
    private fun scrubMediaInfoChild(
        child: IsoBox,
        depth: Int,
    ) {
        when (child.type) {
            HDLR -> editor.zeroHandlerName(child)
            DINF -> walk(child, IsoLevel.DataInfo, depth + 1)
            STBL -> walk(child, IsoLevel.SampleTable, depth + 1)
        }
    }

    /** Remembers the sample tables a track's policy needs; a duplicate table is ambiguous. */
    private fun recordSampleTable(child: IsoBox) {
        val track = currentTrack ?: return
        when (child.type) {
            STSD -> track.sampleDescriptions = child.takeIf { track.sampleDescriptions == null } ?: malformedVideo()
            STSC -> track.sampleToChunk = child.takeIf { track.sampleToChunk == null } ?: malformedVideo()
            STSZ, STZ2 -> track.sampleSizes = child.takeIf { track.sampleSizes == null } ?: malformedVideo()
            STCO, CO64 -> track.chunkOffsets = child.takeIf { track.chunkOffsets == null } ?: malformedVideo()
        }
    }

    /**
     * Applies the track policy once the whole movie box is known: video tracks lose their compressor names, kept
     * media tracks keep their samples, and timed-metadata tracks have every sample zeroed. Returns zeroed bytes.
     */
    private fun finishTrack(
        track: IsoTrackScan,
        samples: IsoSampleZeroing,
    ): Long {
        if (track.externalData) unsupportedVideo()
        return when {
            track.handler == VIDE -> {
                track.sampleDescriptions?.let(editor::zeroVisualEntryNames)
                0L
            }
            track.handler in KEPT_HANDLERS -> 0L
            // Fragment sample tables are not followed, so their timed metadata cannot be removed.
            fragmented -> unsupportedVideo()
            else -> samples.zeroSamples(track)
        }
    }

    companion object {
        private const val MAX_TRACKS = 1024

        // Boxes that only carry descriptive metadata at every level the walk opens.
        private val NEUTRALIZED = setOf("udta", "meta", "uuid", "prft").map(::isoFourCc).toSet()

        // Top-level boxes that make up a movie's layout; every other top-level box is neutralized.
        private val KEPT_TOP_LEVEL = setOf(FTYP, MDAT) + setOf("styp", "sidx", "ssix", "pdin", "mfra").map(::isoFourCc)

        // Track handlers whose samples are audio-visual or user-visible content and are never touched.
        private val KEPT_HANDLERS =
            setOf("vide", "soun", "auxv", "pict", "sbtl", "subt", "text", "clcp").map(::isoFourCc).toSet()

        // First box types that identify ISO BMFF, including QuickTime files written before `ftyp` existed.
        private val LEADING_TYPES = setOf(FTYP, MOOV, MDAT, FREE, SKIP, WIDE)

        /** Whether [reader] starts with a box type that only ISO BMFF and QuickTime files begin with. */
        fun looksLikeIsoBmff(reader: ScrubReader): Boolean {
            val firstType = if (reader.size >= BOX_HEADER_BYTES) reader.u32(Int.SIZE_BYTES.toLong()).toInt() else 0
            return firstType in LEADING_TYPES
        }
    }
}
