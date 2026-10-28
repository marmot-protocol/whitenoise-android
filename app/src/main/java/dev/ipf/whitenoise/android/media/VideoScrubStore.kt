package dev.ipf.whitenoise.android.media

import java.io.EOFException
import java.io.RandomAccessFile

// Bytes read ahead from a file, so a sequential walk over small headers costs one system call per window.
private const val FILE_WINDOW_BYTES = 16 * 1024

// Header and field reads at most this long use the window; table and zero-scan blocks read the file directly.
private const val FILE_WINDOWED_READ_BYTES = 64

// Shared zero source for overwriting file ranges in bounded writes.
private val ZERO_BLOCK = ByteArray(8 * 1024)

/** Random access to the bytes of one video, small enough to back both files and arrays. */
internal interface ScrubStore {
    val size: Long

    /** Reads exactly [length] bytes at [position] into [target] at [offset], or throws an [java.io.IOException]. */
    fun read(
        position: Long,
        target: ByteArray,
        offset: Int,
        length: Int,
    )

    /** Overwrites the bytes at [position] with [length] bytes of [source] from [offset]. */
    fun write(
        position: Long,
        source: ByteArray,
        offset: Int,
        length: Int,
    )

    /** Overwrites [length] bytes at [position] with zeros. */
    fun zero(
        position: Long,
        length: Long,
    )
}

/** Throws an [EOFException] unless [length] bytes at [position] lie inside a store of [size] bytes. */
private fun checkStoreRange(
    position: Long,
    length: Long,
    size: Long,
) {
    if (position < 0L || length < 0L || position > size - length) throw EOFException("range outside the video")
}

/** A writable file with a read-ahead window; writes update the window so later reads see them. */
internal class FileScrubStore(
    private val file: RandomAccessFile,
) : ScrubStore {
    override val size: Long = file.length()
    private val window = ByteArray(FILE_WINDOW_BYTES)
    private var windowStart = -1L
    private var windowLength = 0

    /** Serves header-sized reads from the window, refilling it at [position] when they fall outside it. */
    override fun read(
        position: Long,
        target: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkStoreRange(position, length.toLong(), size)
        if (length > FILE_WINDOWED_READ_BYTES) {
            file.seek(position)
            file.readFully(target, offset, length)
            return
        }
        if (windowStart < 0L || position < windowStart || position + length > windowStart + windowLength) {
            windowLength = minOf(FILE_WINDOW_BYTES.toLong(), size - position).toInt()
            file.seek(position)
            file.readFully(window, 0, windowLength)
            windowStart = position
        }
        System.arraycopy(window, (position - windowStart).toInt(), target, offset, length)
    }

    /** Writes through to the file and patches the overlapping part of the window. */
    override fun write(
        position: Long,
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkStoreRange(position, length.toLong(), size)
        file.seek(position)
        file.write(source, offset, length)
        patchWindow(position, length.toLong()) { windowIndex, sourceIndex ->
            window[windowIndex] = source[offset + sourceIndex]
        }
    }

    /** Writes zeros in bounded blocks and clears the overlapping part of the window. */
    override fun zero(
        position: Long,
        length: Long,
    ) {
        checkStoreRange(position, length, size)
        file.seek(position)
        var remaining = length
        while (remaining > 0L) {
            val block = minOf(remaining, ZERO_BLOCK.size.toLong()).toInt()
            file.write(ZERO_BLOCK, 0, block)
            remaining -= block
        }
        patchWindow(position, length) { windowIndex, _ -> window[windowIndex] = 0 }
    }

    /** Applies [update] to every window byte that overlaps the written range at [position]. */
    private inline fun patchWindow(
        position: Long,
        length: Long,
        update: (windowIndex: Int, sourceIndex: Int) -> Unit,
    ) {
        if (windowStart < 0L) return
        val start = maxOf(position, windowStart)
        val end = minOf(position + length, windowStart + windowLength)
        for (absolute in start until end) {
            update((absolute - windowStart).toInt(), (absolute - position).toInt())
        }
    }
}

/** A caller-owned array scrubbed in place. */
internal class ArrayScrubStore(
    private val bytes: ByteArray,
) : ScrubStore {
    override val size: Long = bytes.size.toLong()

    /** Copies out of the array after a bounds check. */
    override fun read(
        position: Long,
        target: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkStoreRange(position, length.toLong(), size)
        System.arraycopy(bytes, position.toInt(), target, offset, length)
    }

    /** Copies into the array after a bounds check. */
    override fun write(
        position: Long,
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkStoreRange(position, length.toLong(), size)
        System.arraycopy(source, offset, bytes, position.toInt(), length)
    }

    /** Clears the range after a bounds check. */
    override fun zero(
        position: Long,
        length: Long,
    ) {
        checkStoreRange(position, length, size)
        bytes.fill(0, position.toInt(), (position + length).toInt())
    }
}

/**
 * Leaves [original] untouched and copies it only at the first write that changes a byte, so re-checking clean bytes
 * that another owner holds costs no copy. [bytes] is the original array until then.
 */
internal class CopyOnWriteScrubStore(
    private val original: ByteArray,
) : ScrubStore {
    override val size: Long = original.size.toLong()

    var bytes: ByteArray = original
        private set

    /** Reads the current version, the original or its copy. */
    override fun read(
        position: Long,
        target: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkStoreRange(position, length.toLong(), size)
        System.arraycopy(bytes, position.toInt(), target, offset, length)
    }

    /** Writes only when a byte would change, copying the original first. */
    override fun write(
        position: Long,
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkStoreRange(position, length.toLong(), size)
        val start = position.toInt()
        val differs = (0 until length).any { bytes[start + it] != source[offset + it] }
        if (differs) {
            writable()
            System.arraycopy(source, offset, bytes, start, length)
        }
    }

    /** Zeroes only when some byte in the range is not already zero, copying the original first. */
    override fun zero(
        position: Long,
        length: Long,
    ) {
        checkStoreRange(position, length, size)
        val start = position.toInt()
        val end = (position + length).toInt()
        if ((start until end).any { bytes[it] != 0.toByte() }) {
            writable()
            bytes.fill(0, start, end)
        }
    }

    /** Swaps in a private copy before the first change. */
    private fun writable() {
        if (bytes === original) bytes = original.copyOf()
    }
}

/** Big-endian field reads over a [ScrubStore] through one reusable scratch buffer. */
internal class ScrubReader(
    val store: ScrubStore,
) {
    private val scratch = ByteArray(Long.SIZE_BYTES)

    val size: Long get() = store.size

    /** One unsigned byte at [position]. */
    fun u8(position: Long): Int {
        store.read(position, scratch, 0, 1)
        return scratch[0].toInt() and BYTE_MASK
    }

    /** One unsigned big-endian 16-bit value at [position]. */
    fun u16(position: Long): Int = unsigned(position, Short.SIZE_BYTES).toInt()

    /** One unsigned big-endian 32-bit value at [position]. */
    fun u32(position: Long): Long = unsigned(position, Int.SIZE_BYTES)

    /** One big-endian 64-bit value at [position]; values above [Long.MAX_VALUE] read as negative. */
    fun u64(position: Long): Long = unsigned(position, Long.SIZE_BYTES)

    /** The [width] bytes at [position] as one big-endian number. */
    fun unsigned(
        position: Long,
        width: Int,
    ): Long {
        store.read(position, scratch, 0, width)
        var value = 0L
        for (index in 0 until width) {
            value = (value shl Byte.SIZE_BITS) or (scratch[index].toLong() and BYTE_MASK.toLong())
        }
        return value
    }

    /**
     * Whether every byte from [position] to the end of the store is zero, which some writers leave as padding after
     * the last box or element. A non-zero first byte answers from the read window; the rest is read in blocks.
     */
    fun isZeroTail(position: Long): Boolean {
        if (position < size && u8(position) != 0) return false
        val block = ByteArray(ZERO_SCAN_BYTES)
        var cursor = position
        var zero = true
        while (zero && cursor < size) {
            val length = minOf(ZERO_SCAN_BYTES.toLong(), size - cursor).toInt()
            store.read(cursor, block, 0, length)
            zero = (0 until length).all { block[it] == 0.toByte() }
            cursor += length
        }
        return zero
    }

    /** Writes [value] as a big-endian four-character code at [position]. */
    fun writeFourCc(
        position: Long,
        value: Int,
    ) {
        for (index in 0 until Int.SIZE_BYTES) {
            scratch[index] = (value ushr (Byte.SIZE_BITS * (Int.SIZE_BYTES - 1 - index))).toByte()
        }
        store.write(position, scratch, 0, Int.SIZE_BYTES)
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val ZERO_SCAN_BYTES = 4096
    }
}

/**
 * Bounds one walk: the total number of boxes or elements visited and their nesting depth. Hostile files reach a
 * refusal quickly instead of spending unbounded time; real recordings stay far below both limits.
 */
internal class ScrubBudget {
    private var visited = 0

    /** Counts one more structural element, refusing once the walk has visited too many. */
    fun visit() {
        visited += 1
        if (visited > MAX_ELEMENTS) malformedVideo()
    }

    /** Refuses nesting deeper than any supported container needs. */
    fun checkDepth(depth: Int) {
        if (depth > MAX_DEPTH) malformedVideo()
    }

    private companion object {
        const val MAX_ELEMENTS = 4_000_000
        const val MAX_DEPTH = 16
    }
}
