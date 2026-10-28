package dev.ipf.whitenoise.android.media

/** One EBML element: its [id] with the length marker kept, and the offsets of its [start], [dataStart] and [end]. */
internal data class EbmlElement(
    val id: Long,
    val start: Long,
    val dataStart: Long,
    val end: Long,
    val unknownSize: Boolean,
)

/**
 * Scrubs one Matroska or WebM file in place, following the policy documented on [VideoMetadataScrubber]. Only the
 * Segment's Info and Tracks are opened. Clusters are skipped by size, and an unknown-size Segment or Cluster ends at
 * the first element that can only appear at its own level.
 */
internal class MatroskaMetadataScrubber(
    private val reader: ScrubReader,
) : ContainerWalk {
    private val budget = ScrubBudget()
    private var voided = 0
    private var zeroedFields = 0

    /** TrackEntries whose TrackType is video, counted as the walk meets them, so a refused walk still reports them. */
    override var videoTracks = 0
        private set

    /** Validates the EBML header, scrubs every Segment and summarizes the rewrite. */
    override fun scrub(): VideoScrubSummary {
        val header = readElement(0L, reader.size)
        if (header.id != EBML || header.unknownSize) malformedVideo()
        checkDocType(header)
        var segments = 0
        var position = header.end
        while (position < reader.size && !reader.isZeroTail(position)) {
            val element = readElement(position, reader.size)
            position =
                when (element.id) {
                    SEGMENT -> walkSegment(element).also { segments += 1 }
                    VOID -> element.end.also { zeroData(element) }
                    else -> malformedVideo()
                }
        }
        if (segments == 0) malformedVideo()
        return VideoScrubSummary(VideoContainer.Matroska, voided, zeroedFields, 0L, videoTracks)
    }

    /** Only Matroska and WebM document types are understood, and a missing DocType means Matroska. */
    private fun checkDocType(header: EbmlElement) {
        forEachChild(header, depth = 1) { child ->
            if (child.id == DOC_TYPE) {
                val length = child.end - child.dataStart
                if (length > MAX_DOC_TYPE_BYTES) unsupportedVideo()
                val bytes = ByteArray(length.toInt())
                reader.store.read(child.dataStart, bytes, 0, bytes.size)
                val docType = String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
                if (docType != "matroska" && docType != "webm") unsupportedVideo()
            }
        }
    }

    /** Scrubs the Segment's children and returns where it really ends, which an unknown size leaves open. */
    private fun walkSegment(segment: EbmlElement): Long {
        var position = segment.dataStart
        while (position < segment.end && !openSegmentEndsAt(segment, position)) {
            val element = readElement(position, segment.end)
            val child = if (element.unknownSize) withUnknownClusterEnd(element) else element
            when (child.id) {
                INFO, TRACKS -> walkMaster(child, depth = 2)
                SEEK_HEAD, CLUSTER, CUES -> Unit
                VOID -> zeroData(child)
                // A Segment or EBML header can only follow an open-ended Segment, never sit inside a sized one.
                SEGMENT, EBML -> malformedVideo()
                // Tags, Attachments, Chapters, CRC-32 and every unknown segment-level element.
                else -> voidElement(child)
            }
            position = child.end
        }
        return position
    }

    /** Whether an open-ended Segment stops at [position]: at zero padding, or where the next Segment or file starts. */
    private fun openSegmentEndsAt(
        segment: EbmlElement,
        position: Long,
    ): Boolean =
        segment.unknownSize &&
            (reader.isZeroTail(position) || readElement(position, segment.end).id.let { it == SEGMENT || it == EBML })

    /** Ends an unknown-size Cluster at its first segment-level sibling, the end of its parent or zero padding. */
    private fun withUnknownClusterEnd(cluster: EbmlElement): EbmlElement {
        if (cluster.id != CLUSTER) malformedVideo()
        var position = cluster.dataStart
        while (position < cluster.end && !reader.isZeroTail(position)) {
            val child = readElement(position, cluster.end)
            if (child.id in SEGMENT_LEVEL) break
            if (child.unknownSize) malformedVideo()
            position = child.end
        }
        return cluster.copy(end = position, unknownSize = false)
    }

    /** Rewrites the private fields of Info, Tracks and each TrackEntry, and the CRC-32 their edits invalidate. */
    private fun walkMaster(
        master: EbmlElement,
        depth: Int,
    ) {
        forEachChild(master, depth) { child ->
            when (child.id) {
                VOID -> zeroData(child)
                CRC32, in VOIDED_FIELDS -> voidElement(child)
                in ZEROED_FIELDS -> {
                    zeroData(child)
                    zeroedFields += 1
                }
                TRACK_ENTRY -> if (master.id == TRACKS) walkMaster(child, depth + 1)
                TRACK_TYPE -> if (master.id == TRACK_ENTRY) countVideoTrack(child)
            }
        }
    }

    /** Counts a TrackEntry whose TrackType, an unsigned integer of 1 to 8 bytes, says video. */
    private fun countVideoTrack(trackType: EbmlElement) {
        val length = trackType.end - trackType.dataStart
        if (length !in 1..Long.SIZE_BYTES) malformedVideo()
        if (reader.unsigned(trackType.dataStart, length.toInt()) == VIDEO_TRACK_TYPE) videoTracks += 1
    }

    /** Visits the sized children of [master]. An unknown-size child is only valid for Segments and Clusters. */
    private inline fun forEachChild(
        master: EbmlElement,
        depth: Int,
        action: (EbmlElement) -> Unit,
    ) {
        budget.checkDepth(depth)
        var position = master.dataStart
        while (position < master.end) {
            val child = readElement(position, master.end)
            if (child.unknownSize) malformedVideo()
            action(child)
            position = child.end
        }
    }

    /** Replaces [element] with a Void element of exactly its length and a zeroed body. */
    private fun voidElement(element: EbmlElement) {
        val total = element.end - element.start
        val header =
            if (total - 2 <= MAX_ONE_BYTE_SIZE) {
                byteArrayOf(VOID.toByte(), (VINT_ONE_BYTE_MARKER or (total - 2).toInt()).toByte())
            } else {
                val body = total - LONG_VOID_HEADER_BYTES
                ByteArray(LONG_VOID_HEADER_BYTES).also { bytes ->
                    bytes[0] = VOID.toByte()
                    bytes[1] = 1
                    for (index in 2 until LONG_VOID_HEADER_BYTES) {
                        val shift = Byte.SIZE_BITS * (LONG_VOID_HEADER_BYTES - 1 - index)
                        bytes[index] = (body ushr shift).toByte()
                    }
                }
            }
        reader.store.write(element.start, header, 0, header.size)
        reader.store.zero(element.start + header.size, total - header.size)
        voided += 1
    }

    /** Zeroes the data of [element], keeping its header. */
    private fun zeroData(element: EbmlElement) {
        if (element.end > element.dataStart) reader.store.zero(element.dataStart, element.end - element.dataStart)
    }

    /**
     * Reads the element header at [position]: a 1 to 4 byte ID and a 1 to 8 byte size. An unknown (all ones) size
     * runs to [limit], and any other size must fit inside it.
     */
    private fun readElement(
        position: Long,
        limit: Long,
    ): EbmlElement {
        budget.visit()
        val idLength = reader.vintLength(position, limit, MAX_ID_BYTES)
        val id = reader.unsigned(position, idLength)
        val sizePosition = position + idLength
        val sizeLength = reader.vintLength(sizePosition, limit, MAX_SIZE_BYTES)
        val valueBits = (Byte.SIZE_BITS - 1) * sizeLength
        val unknownValue = (1L shl valueBits) - 1
        val size = reader.unsigned(sizePosition, sizeLength) and unknownValue
        val dataStart = sizePosition + sizeLength
        val unknown = size == unknownValue
        if (!unknown && size > limit - dataStart) malformedVideo()
        return EbmlElement(id, position, dataStart, if (unknown) limit else dataStart + size, unknown)
    }

    private companion object {
        const val MAX_ID_BYTES = 4
        const val MAX_SIZE_BYTES = 8
        const val MAX_DOC_TYPE_BYTES = 32L
        const val MAX_ONE_BYTE_SIZE = 126L
        const val VINT_ONE_BYTE_MARKER = 0x80
        const val LONG_VOID_HEADER_BYTES = 9

        const val EBML = 0x1A45DFA3L
        const val DOC_TYPE = 0x4282L
        const val SEGMENT = 0x18538067L
        const val SEEK_HEAD = 0x114D9B74L
        const val INFO = 0x1549A966L
        const val TRACKS = 0x1654AE6BL
        const val CLUSTER = 0x1F43B675L
        const val CUES = 0x1C53BB6BL
        const val TAGS = 0x1254C367L
        const val ATTACHMENTS = 0x1941A469L
        const val CHAPTERS = 0x1043A770L
        const val VOID = 0xECL
        const val CRC32 = 0xBFL
        const val TRACK_ENTRY = 0xAEL
        const val TRACK_TYPE = 0x83L
        const val VIDEO_TRACK_TYPE = 1L

        // Optional strings and dates that identify the recording, its files or its device: removed entirely.
        val VOIDED_FIELDS =
            setOf(
                0x7384L, // SegmentFilename
                0x3C83ABL, // PrevFilename
                0x3E83BBL, // NextFilename
                0x4461L, // DateUTC
                0x7BA9L, // Title
                0x536EL, // TrackEntry Name
                0x258688L, // CodecName
            )

        // Mandatory application strings: kept as empty (zero-filled) strings.
        val ZEROED_FIELDS =
            setOf(
                0x4D80L, // MuxingApp
                0x5741L, // WritingApp
            )

        // IDs that can only start a new Segment-level element, so they end an unknown-size Cluster.
        val SEGMENT_LEVEL = setOf(CLUSTER, CUES, TAGS, ATTACHMENTS, CHAPTERS, INFO, TRACKS, SEEK_HEAD, SEGMENT, EBML)
    }
}

/** The byte length of the variable-length integer at [position], from its leading marker bit, at most [maxLength]. */
private fun ScrubReader.vintLength(
    position: Long,
    limit: Long,
    maxLength: Int,
): Int {
    if (position >= limit) malformedVideo()
    val first = u8(position)
    if (first == 0) malformedVideo()
    val length = Integer.numberOfLeadingZeros(first) - (Int.SIZE_BITS - Byte.SIZE_BITS) + 1
    if (length > maxLength || length > limit - position) malformedVideo()
    return length
}
