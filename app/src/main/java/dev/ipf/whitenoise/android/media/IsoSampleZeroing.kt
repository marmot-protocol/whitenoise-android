package dev.ipf.whitenoise.android.media

/**
 * Payload ranges of the top-level `mdat` boxes. A timed-metadata sample may only be zeroed inside one of them, so a
 * crafted sample table can never rewrite the movie structure or bytes outside the media data.
 */
internal class MediaDataRanges {
    private val bounds = LongArray(MAX_RANGES * 2)
    private var count = 0

    /** Records one `mdat` payload; ranges beyond the cap are not recorded, so samples there are refused. */
    fun add(
        start: Long,
        end: Long,
    ) {
        if (count == MAX_RANGES) return
        bounds[count * 2] = start
        bounds[count * 2 + 1] = end
        count += 1
    }

    /** Whether `[start, end)` lies inside one recorded `mdat` payload. */
    fun contains(
        start: Long,
        end: Long,
    ): Boolean = (0 until count).any { index -> start >= bounds[index * 2] && end <= bounds[index * 2 + 1] }

    private companion object {
        const val MAX_RANGES = 1024
    }
}

/**
 * Streams the big-endian entries of one sample table through a fixed buffer, so a table of any declared length costs
 * the same memory. Reading past the table's end refuses the file.
 */
internal class TableCursor(
    private val reader: ScrubReader,
    private var position: Long,
    private val end: Long,
) {
    private val buffer = ByteArray(BUFFER_BYTES)
    private var buffered = 0
    private var index = 0

    /** The next [width]-byte unsigned entry. */
    fun next(width: Int): Long {
        var value = 0L
        repeat(width) { value = (value shl Byte.SIZE_BITS) or nextByte().toLong() }
        return value
    }

    /** The next unsigned byte, refilling the buffer from the table when it runs out. */
    fun nextByte(): Int {
        if (index == buffered) {
            val length = minOf(BUFFER_BYTES.toLong(), end - position).toInt()
            if (length <= 0) malformedVideo()
            reader.store.read(position, buffer, 0, length)
            position += length
            buffered = length
            index = 0
        }
        return buffer[index++].toInt() and BYTE_MASK
    }

    private companion object {
        const val BUFFER_BYTES = 4096
        const val BYTE_MASK = 0xFF
    }
}

/** The sample sizes of one track from `stsz` (constant or 32-bit) or `stz2` (4, 8 or 16-bit fields). */
internal class SampleSizes(
    private val reader: ScrubReader,
    box: IsoBox,
) {
    private val constantSize: Long
    private val fieldBits: Int
    private val count: Long
    private val cursor: TableCursor?
    private var consumed = 0L
    private var pendingNibble = -1

    init {
        if (box.payloadSize < TABLE_HEADER_BYTES) malformedVideo()
        count = reader.u32(box.payload + COUNT_OFFSET)
        if (box.type == IsoBoxTypes.STZ2) {
            constantSize = 0L
            fieldBits = reader.u8(box.payload + FIELD_SIZE_OFFSET)
            if (fieldBits !in COMPACT_FIELD_BITS) malformedVideo()
        } else {
            constantSize = reader.u32(box.payload + FULL_BOX_FIELDS)
            fieldBits = Int.SIZE_BITS
        }
        val tableBytes = if (constantSize > 0L) 0L else (count * fieldBits + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
        if (box.payloadSize - TABLE_HEADER_BYTES < tableBytes) malformedVideo()
        val tableStart = box.payload + TABLE_HEADER_BYTES
        cursor = if (constantSize > 0L) null else TableCursor(reader, tableStart, tableStart + tableBytes)
    }

    /** Whether every declared sample has been consumed by a chunk. */
    val exhausted: Boolean get() = consumed == count

    /** The total size in bytes of the next [samples] samples, which must lie inside this file. */
    fun take(samples: Long): Long {
        if (samples > count - consumed) malformedVideo()
        consumed += samples
        if (cursor == null) {
            if (samples > 0L && constantSize > reader.size / samples) malformedVideo()
            return samples * constantSize
        }
        var total = 0L
        var remaining = samples
        while (remaining > 0L) {
            total += nextSize(cursor)
            if (total > reader.size) malformedVideo()
            remaining -= 1
        }
        return total
    }

    /** One table entry; 4-bit fields hold two samples per byte, the first in the high nibble. */
    private fun nextSize(cursor: TableCursor): Long =
        when {
            fieldBits != NIBBLE_BITS -> cursor.next(fieldBits / Byte.SIZE_BITS)
            pendingNibble >= 0 -> pendingNibble.toLong().also { pendingNibble = -1 }
            else -> {
                val pair = cursor.nextByte()
                pendingNibble = pair and NIBBLE_MASK
                (pair shr NIBBLE_BITS).toLong()
            }
        }

    private companion object {
        const val FULL_BOX_FIELDS = 4L
        const val FIELD_SIZE_OFFSET = 7L
        const val COUNT_OFFSET = 8L
        const val TABLE_HEADER_BYTES = 12L
        const val NIBBLE_BITS = 4
        const val NIBBLE_MASK = 0x0F

        // `stz2` stores each sample size in 4, 8 or 16 bits.
        val COMPACT_FIELD_BITS = setOf(NIBBLE_BITS, Byte.SIZE_BITS, Short.SIZE_BITS)
    }
}

/** The `stsc` runs: how many samples each chunk holds, from strictly increasing 1-based first chunks. */
internal class SampleToChunkRuns(
    reader: ScrubReader,
    box: IsoBox,
) {
    private val cursor: TableCursor
    private var remainingEntries: Long
    private var currentFirst = 0L
    private var currentSamples = 0L
    private var nextFirst = 0L
    private var nextSamples = 0L

    init {
        if (box.payloadSize < HEADER_BYTES) malformedVideo()
        remainingEntries = reader.u32(box.payload + FULL_BOX_FIELDS)
        if (box.payloadSize - HEADER_BYTES < remainingEntries * ENTRY_BYTES) malformedVideo()
        val start = box.payload + HEADER_BYTES
        cursor = TableCursor(reader, start, start + remainingEntries * ENTRY_BYTES)
        readNext()
    }

    /**
     * Samples in the 1-based [chunk]; chunks are asked for in increasing order from 1, so a table whose first run
     * does not start at chunk 1 is refused before any chunk is zeroed.
     */
    fun samplesIn(chunk: Long): Long {
        while (nextFirst != 0L && chunk >= nextFirst) {
            if (nextFirst <= currentFirst) malformedVideo()
            currentFirst = nextFirst
            currentSamples = nextSamples
            readNext()
        }
        if (currentFirst == 0L) malformedVideo()
        return currentSamples
    }

    /** Loads the following run, or marks that none is left. */
    private fun readNext() {
        if (remainingEntries == 0L) {
            nextFirst = 0L
            return
        }
        remainingEntries -= 1
        nextFirst = cursor.next(Int.SIZE_BYTES)
        nextSamples = cursor.next(Int.SIZE_BYTES)
        cursor.next(Int.SIZE_BYTES)
        if (nextFirst == 0L) malformedVideo()
    }

    private companion object {
        const val FULL_BOX_FIELDS = 4L
        const val HEADER_BYTES = 8L
        const val ENTRY_BYTES = 12L
    }
}

/** Zeroes every sample of a timed-metadata track inside its `mdat`, walking chunks through streamed tables. */
internal class IsoSampleZeroing(
    private val reader: ScrubReader,
    private val mediaData: MediaDataRanges,
) {
    /** Zeroes [track]'s samples and returns how many bytes that covered. A track without samples is left alone. */
    fun zeroSamples(track: IsoTrackScan): Long {
        val offsets = track.chunkOffsets
        val runs = track.sampleToChunk
        val sizes = track.sampleSizes
        if (offsets == null && runs == null && sizes == null) return 0L
        if (offsets == null || runs == null || sizes == null) malformedVideo()
        return zeroChunks(ChunkOffsets(reader, offsets), SampleToChunkRuns(reader, runs), SampleSizes(reader, sizes))
    }

    /** Zeroes each chunk's samples as one range, which must lie inside one `mdat` payload. */
    private fun zeroChunks(
        chunks: ChunkOffsets,
        runs: SampleToChunkRuns,
        sizes: SampleSizes,
    ): Long {
        var zeroed = 0L
        var chunk = 1L
        while (chunk <= chunks.count) {
            val offset = chunks.next()
            val length = sizes.take(runs.samplesIn(chunk))
            if (length > 0L) {
                if (offset < 0L || offset > reader.size - length) malformedVideo()
                if (!mediaData.contains(offset, offset + length)) malformedVideo()
                reader.store.zero(offset, length)
                zeroed += length
            }
            chunk += 1
        }
        if (!sizes.exhausted) malformedVideo()
        return zeroed
    }
}

/** The chunk offsets of one track from `stco` (32-bit) or `co64` (64-bit), streamed in order. */
internal class ChunkOffsets(
    reader: ScrubReader,
    box: IsoBox,
) {
    private val width = if (box.type == IsoBoxTypes.CO64) Long.SIZE_BYTES else Int.SIZE_BYTES
    private val cursor: TableCursor

    /** How many chunks the table declares; it was checked against the table's length. */
    val count: Long

    init {
        if (box.payloadSize < TABLE_HEADER_BYTES) malformedVideo()
        count = reader.u32(box.payload + FULL_BOX_FIELDS)
        if (box.payloadSize - TABLE_HEADER_BYTES < count * width) malformedVideo()
        val start = box.payload + TABLE_HEADER_BYTES
        cursor = TableCursor(reader, start, start + count * width)
    }

    /** The next chunk's file offset; a 64-bit offset above [Long.MAX_VALUE] reads as negative. */
    fun next(): Long = cursor.next(width)

    private companion object {
        const val FULL_BOX_FIELDS = 4L
        const val TABLE_HEADER_BYTES = 8L
    }
}
