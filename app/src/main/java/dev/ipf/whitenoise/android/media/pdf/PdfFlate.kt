package dev.ipf.whitenoise.android.media.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Adler32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

private const val CHUNK = 16 * 1024
private const val ZLIB_HEADER_SIZE = 2
private const val ADLER_SIZE = 4
private const val STORED_HEADER_SIZE = 5
private const val STORED_MAX = 0xFFFF
private const val ZLIB_CMF = 0x78
private const val ZLIB_FLG_BEST = 0xDA
private const val SPACE = ' '.code.toByte()
private const val SHIFT_BYTE = 8
private const val SHIFT_2 = 16
private const val SHIFT_3 = 24

/** Bounded zlib (`/FlateDecode`) reading and same-length rewriting for metadata streams. */
internal object PdfFlate {
    /** Inflates [compressed], or returns null when it is not zlib data or inflates past [limit] bytes. */
    @Suppress("ReturnCount") // Not zlib, too large and finished are separate exits.)
    fun inflate(
        compressed: ByteArray,
        limit: Int,
    ): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(CHUNK)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                if (output.size() + count > limit) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    /**
     * Encodes [prefix] then [suffix] as one zlib stream of exactly [length] bytes, or returns null when it cannot
     * fit. [prefix] is compressed; [suffix] follows in stored blocks after enough spaces to fill [length], so the
     * stream decodes to [prefix], those spaces, then [suffix], and ends exactly where the original data ended.
     */
    fun encodeExactly(
        prefix: ByteArray,
        suffix: ByteArray,
        length: Int,
    ): ByteArray? {
        val compressed = deflateFlushed(prefix)
        val room = length - ZLIB_HEADER_SIZE - compressed.size - ADLER_SIZE
        var blocks = 1
        while (room - STORED_HEADER_SIZE * blocks > STORED_MAX * blocks) blocks++
        val stored = room - STORED_HEADER_SIZE * blocks
        if (stored < suffix.size) return null
        val tail = ByteArray(stored - suffix.size) { SPACE } + suffix
        val output = ByteArrayOutputStream(length)
        output.write(ZLIB_CMF)
        output.write(ZLIB_FLG_BEST)
        output.write(compressed)
        writeStoredBlocks(output, tail, blocks)
        val adler = Adler32().apply { update(prefix) }.apply { update(tail) }.value
        output.write((adler ushr SHIFT_3).toInt())
        output.write((adler ushr SHIFT_2).toInt())
        output.write((adler ushr SHIFT_BYTE).toInt())
        output.write(adler.toInt())
        return output.toByteArray().takeIf { it.size == length }
    }

    /** Raw deflate of [input] ending on a byte boundary with no final block, so stored blocks can follow. */
    private fun deflateFlushed(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setInput(input)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(CHUNK)
            do {
                val count = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
                output.write(buffer, 0, count)
            } while (count == buffer.size)
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Writes [data] as [blocks] stored deflate blocks of near-equal size, the last one final. */
    private fun writeStoredBlocks(
        output: ByteArrayOutputStream,
        data: ByteArray,
        blocks: Int,
    ) {
        var offset = 0
        for (block in 0 until blocks) {
            val size = (data.size - offset) / (blocks - block)
            output.write(if (block == blocks - 1) 1 else 0)
            output.write(size and BYTE_MASK)
            output.write(size ushr SHIFT_BYTE)
            output.write(size.inv() and BYTE_MASK)
            output.write((size.inv() ushr SHIFT_BYTE) and BYTE_MASK)
            output.write(data, offset, size)
            offset += size
        }
    }
}
