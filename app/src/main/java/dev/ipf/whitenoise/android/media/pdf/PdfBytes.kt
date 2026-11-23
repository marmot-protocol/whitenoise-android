package dev.ipf.whitenoise.android.media.pdf

import java.nio.ByteBuffer

/** Random access to the bytes of one PDF, backed by an array or a mapped file. */
internal interface PdfBytes {
    val size: Int

    /** The unsigned byte at [index]. */
    operator fun get(index: Int): Int

    /** A copy of the bytes from [from] until [until]. */
    fun copy(
        from: Int,
        until: Int,
    ): ByteArray

    /** Overwrites the bytes at [position] with [source]. */
    fun write(
        position: Int,
        source: ByteArray,
    )
}

/** A PDF held in memory. Writes change [bytes] in place. */
internal class ArrayPdfBytes(
    private val bytes: ByteArray,
) : PdfBytes {
    override val size: Int get() = bytes.size

    override fun get(index: Int): Int = bytes[index].toInt() and BYTE_MASK

    override fun copy(
        from: Int,
        until: Int,
    ): ByteArray = bytes.copyOfRange(from, until)

    override fun write(
        position: Int,
        source: ByteArray,
    ) {
        source.copyInto(bytes, position)
    }
}

/** A PDF read through [buffer], usually a mapped file. Writes reach the file through the mapping. */
internal class BufferPdfBytes(
    private val buffer: ByteBuffer,
) : PdfBytes {
    override val size: Int = buffer.limit()

    override fun get(index: Int): Int = buffer.get(index).toInt() and BYTE_MASK

    override fun copy(
        from: Int,
        until: Int,
    ): ByteArray {
        val target = ByteArray(until - from)
        buffer.duplicate().apply { position(from) }.get(target)
        return target
    }

    override fun write(
        position: Int,
        source: ByteArray,
    ) {
        buffer.duplicate().apply { position(position) }.put(source)
    }
}

internal const val BYTE_MASK = 0xFF
