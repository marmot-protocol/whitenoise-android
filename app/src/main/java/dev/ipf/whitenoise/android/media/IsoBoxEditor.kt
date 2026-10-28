package dev.ipf.whitenoise.android.media

/** One ISO BMFF box: its four-character [type] and the offsets of its [start], [payload] and [end]. */
internal data class IsoBox(
    val type: Int,
    val start: Long,
    val payload: Long,
    val end: Long,
) {
    val payloadSize: Long get() = end - payload
}

/** A four-character code as the big-endian int it is stored as. */
internal fun isoFourCc(code: String): Int = code.fold(0) { value, char -> (value shl Byte.SIZE_BITS) or char.code }

/** Box types the ISO BMFF scrubber reads or writes. */
internal object IsoBoxTypes {
    val FTYP = isoFourCc("ftyp")
    val MOOV = isoFourCc("moov")
    val MDAT = isoFourCc("mdat")
    val MOOF = isoFourCc("moof")
    val FREE = isoFourCc("free")
    val SKIP = isoFourCc("skip")
    val WIDE = isoFourCc("wide")
    val MVHD = isoFourCc("mvhd")
    val TRAK = isoFourCc("trak")
    val MVEX = isoFourCc("mvex")
    val CMOV = isoFourCc("cmov")
    val TKHD = isoFourCc("tkhd")
    val MDIA = isoFourCc("mdia")
    val MDHD = isoFourCc("mdhd")
    val HDLR = isoFourCc("hdlr")
    val MINF = isoFourCc("minf")
    val DINF = isoFourCc("dinf")
    val DREF = isoFourCc("dref")
    val STBL = isoFourCc("stbl")
    val STSD = isoFourCc("stsd")
    val STSC = isoFourCc("stsc")
    val STSZ = isoFourCc("stsz")
    val STZ2 = isoFourCc("stz2")
    val STCO = isoFourCc("stco")
    val CO64 = isoFourCc("co64")
    val TRAF = isoFourCc("traf")
    val VIDE = isoFourCc("vide")
}

/**
 * Reads ISO BMFF box headers and rewrites individual boxes and fields in place. Counts what it removed, and charges
 * every header it reads to the walk's [budget].
 */
internal class IsoBoxEditor(
    private val reader: ScrubReader,
    private val budget: ScrubBudget,
) {
    var neutralized = 0
        private set
    var zeroedFields = 0
        private set

    /**
     * Reads the box header at [position], refusing one that does not fit inside [limit]. A 64-bit largesize is
     * supported; a zero size runs to [limit] and is only valid at the top level ([allowToEnd]).
     */
    fun readBox(
        position: Long,
        limit: Long,
        allowToEnd: Boolean,
    ): IsoBox {
        budget.visit()
        if (limit - position < BOX_HEADER_BYTES) malformedVideo()
        val declared = reader.u32(position)
        val type = reader.u32(position + Int.SIZE_BYTES).toInt()
        val headerSize = if (declared == LARGE_SIZE) LARGE_BOX_HEADER_BYTES else BOX_HEADER_BYTES
        if (limit - position < headerSize) malformedVideo()
        val size =
            when (declared) {
                LARGE_SIZE -> reader.u64(position + BOX_HEADER_BYTES)
                0L -> if (allowToEnd) limit - position else malformedVideo()
                else -> declared
            }
        if (size < headerSize || size > limit - position) malformedVideo()
        return IsoBox(type, position, position + headerSize, position + size)
    }

    /** Turns [box] into a `free` box of the same size with a zeroed payload. */
    fun neutralize(box: IsoBox) {
        reader.writeFourCc(box.start + Int.SIZE_BYTES, IsoBoxTypes.FREE)
        zeroPayload(box)
        neutralized += 1
    }

    /** Zeroes everything after the header of [box]. */
    fun zeroPayload(box: IsoBox) {
        if (box.payloadSize > 0L) reader.store.zero(box.payload, box.payloadSize)
    }

    /** Zeroes the creation and modification times of a version 0 or 1 `mvhd`, `tkhd` or `mdhd`. */
    fun zeroTimes(box: IsoBox) {
        if (box.payloadSize < FULL_BOX_FIELDS) malformedVideo()
        val width =
            when (reader.u8(box.payload)) {
                0 -> Int.SIZE_BYTES.toLong()
                1 -> Long.SIZE_BYTES.toLong()
                else -> malformedVideo()
            }
        if (box.payloadSize < FULL_BOX_FIELDS + 2 * width) malformedVideo()
        reader.store.zero(box.payload + FULL_BOX_FIELDS, 2 * width)
        zeroedFields += 1
    }

    /**
     * Zeroes the name after a handler's fixed fields, an empty string in both ISO and QuickTime form, and returns
     * the handler type.
     */
    fun zeroHandlerName(hdlr: IsoBox): Int {
        if (hdlr.payloadSize < HANDLER_NAME_OFFSET) malformedVideo()
        val nameStart = hdlr.payload + HANDLER_NAME_OFFSET
        if (hdlr.end > nameStart) {
            reader.store.zero(nameStart, hdlr.end - nameStart)
            zeroedFields += 1
        }
        return reader.u32(hdlr.payload + HANDLER_TYPE_OFFSET).toInt()
    }

    /** Zeroes the vendor and compressor name of every visual sample entry in a video track's `stsd`. */
    fun zeroVisualEntryNames(stsd: IsoBox) {
        forEachEntry(stsd) { entry ->
            if (entry.payloadSize >= COMPRESSOR_NAME_END) {
                reader.store.zero(entry.payload + VENDOR_OFFSET, Int.SIZE_BYTES.toLong())
                reader.store.zero(entry.payload + COMPRESSOR_NAME_OFFSET, COMPRESSOR_NAME_END - COMPRESSOR_NAME_OFFSET)
                zeroedFields += 1
            }
        }
    }

    /** Whether every entry of a `dref` is self-contained; any other entry names a file outside this one. */
    fun dataReferencesSelfContained(dref: IsoBox): Boolean {
        var selfContained = true
        forEachEntry(dref) { entry ->
            if (entry.payloadSize < FULL_BOX_FIELDS) malformedVideo()
            if (reader.u32(entry.payload) and SELF_CONTAINED_FLAG == 0L) selfContained = false
        }
        return selfContained
    }

    /** Visits the declared entries of a full box that holds an entry count and then child boxes. */
    private inline fun forEachEntry(
        box: IsoBox,
        action: (IsoBox) -> Unit,
    ) {
        if (box.payloadSize < FULL_BOX_FIELDS + Int.SIZE_BYTES) malformedVideo()
        val count = reader.u32(box.payload + FULL_BOX_FIELDS)
        var position = box.payload + FULL_BOX_FIELDS + Int.SIZE_BYTES
        var index = 0L
        while (index < count) {
            val entry = readBox(position, box.end, allowToEnd = false)
            action(entry)
            position = entry.end
            index += 1
        }
    }

    companion object {
        const val BOX_HEADER_BYTES = 8
        private const val LARGE_BOX_HEADER_BYTES = 16
        private const val LARGE_SIZE = 1L
        private const val FULL_BOX_FIELDS = 4L
        private const val SELF_CONTAINED_FLAG = 1L
        private const val HANDLER_TYPE_OFFSET = 8L
        private const val HANDLER_NAME_OFFSET = 24L

        // Visual sample entry fields, counted from the end of the entry's box header.
        private const val VENDOR_OFFSET = 12L
        private const val COMPRESSOR_NAME_OFFSET = 42L
        private const val COMPRESSOR_NAME_END = 74L
    }
}
