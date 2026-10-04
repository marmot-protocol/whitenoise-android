package dev.ipf.whitenoise.android.media

import java.io.ByteArrayOutputStream

/**
 * Hand-built GIF and WebP containers for animation admission tests. Only container
 * structure and fixed frame headers are meaningful: the LZW, VP8 and VP8L payloads
 * after those headers are filler, so these bytes must not be used as pixel-decoder
 * fixtures.
 */
internal object AnimationSourceFixtures {
    // ---- GIF ---------------------------------------------------------------

    fun gif(
        width: Int = 4,
        height: Int = 4,
        frames: List<ByteArray> = listOf(gifFrame(), gifFrame()),
        extensions: List<ByteArray> = listOf(gifLoopExtension()),
        trailer: Boolean = true,
    ): ByteArray =
        bytes {
            write("GIF89a".encodeToByteArray())
            write(u16le(width))
            write(u16le(height))
            write(byteArrayOf(0x80.toByte(), 0, 0)) // Two-entry global colour table.
            write(byteArrayOf(0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
            extensions.forEach { write(it) }
            frames.forEach { write(it) }
            if (trailer) write(0x3b)
        }

    fun gifFrame(
        left: Int = 0,
        top: Int = 0,
        width: Int = 1,
        height: Int = 1,
        packed: Int = 0,
        localTableBytes: Int = 0,
        lzwMinimumCodeSize: Int = 2,
        graphicControl: Boolean = true,
    ): ByteArray =
        bytes {
            if (graphicControl) write(byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0))
            write(0x2c)
            write(u16le(left))
            write(u16le(top))
            write(u16le(width))
            write(u16le(height))
            write(packed)
            write(ByteArray(localTableBytes))
            write(lzwMinimumCodeSize)
            write(byteArrayOf(2, 0x4c, 0x01, 0)) // One filler sub-block, then the terminator.
        }

    fun gifLoopExtension(): ByteArray = byteArrayOf(0x21, 0xff.toByte(), 11) + "NETSCAPE2.0".encodeToByteArray() + byteArrayOf(3, 1, 0, 0, 0)

    fun gifExtension(
        label: Int,
        vararg blocks: ByteArray,
    ): ByteArray =
        bytes {
            write(0x21)
            write(label)
            blocks.forEach {
                write(it.size)
                write(it)
            }
            write(0)
        }

    // ---- WebP --------------------------------------------------------------

    fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".encodeToByteArray() + chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        return "RIFF".encodeToByteArray() + u32le(body.size.toLong()) + body
    }

    fun chunk(
        tag: String,
        payload: ByteArray,
        pad: Boolean = true,
    ): ByteArray {
        require(tag.length == 4)
        val padding = if (pad && payload.size % 2 == 1) ByteArray(1) else ByteArray(0)
        return tag.encodeToByteArray() + u32le(payload.size.toLong()) + payload + padding
    }

    /** VP8L header for [width] x [height], followed by filler bytes. */
    fun vp8l(
        width: Int,
        height: Int,
        version: Int = 0,
    ): ByteArray {
        val packed = (width - 1).toLong() or ((height - 1).toLong() shl 14) or (version.toLong() shl 29)
        return chunk("VP8L", byteArrayOf(0x2f) + u32le(packed) + byteArrayOf(0, 0, 0))
    }

    /** VP8 key-frame header for [width] x [height], followed by filler bytes. */
    fun vp8(
        width: Int,
        height: Int,
        frameTag: Int = (1 shl 4) or (4 shl 5),
        startCode: ByteArray = byteArrayOf(0x9d.toByte(), 0x01, 0x2a),
    ): ByteArray =
        chunk(
            "VP8 ",
            u24le(frameTag) + startCode + u16le(width) + u16le(height) + ByteArray(6),
        )

    fun vp8x(
        width: Int,
        height: Int,
        flags: Int,
        reserved: Int = 0,
    ): ByteArray = chunk("VP8X", byteArrayOf(flags.toByte()) + u24le(reserved) + u24le(width - 1) + u24le(height - 1))

    fun anim(): ByteArray = chunk("ANIM", byteArrayOf(0, 0, 0, 0, 0, 0))

    fun anmf(
        left: Int = 0,
        top: Int = 0,
        width: Int = 1,
        height: Int = 1,
        flags: Int = 0,
        frameData: List<ByteArray> = listOf(vp8l(width, height)),
    ): ByteArray {
        require(left % 2 == 0 && top % 2 == 0)
        val rectangle = u24le(left / 2) + u24le(top / 2) + u24le(width - 1) + u24le(height - 1)
        val header = rectangle + u24le(100) + byteArrayOf(flags.toByte()) // 100 ms, then blend/dispose flags.
        return chunk("ANMF", frameData.fold(header) { acc, chunk -> acc + chunk })
    }

    /** ALPH chunk with the given header byte and payload length (including the header byte). */
    fun alph(
        header: Int,
        totalBytes: Int,
    ): ByteArray = chunk("ALPH", byteArrayOf(header.toByte()) + ByteArray(totalBytes - 1))

    fun animatedWebp(
        width: Int = 4,
        height: Int = 4,
        frames: List<ByteArray> = listOf(anmf(), anmf(left = 2, top = 2, width = 2, height = 2)),
    ): ByteArray = riff(vp8x(width, height, flags = VP8X_ANIMATION), anim(), *frames.toTypedArray())

    const val VP8X_ANIMATION = 0x02

    // ---- byte helpers ------------------------------------------------------

    fun u16le(value: Int): ByteArray = byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte())

    fun u24le(value: Int): ByteArray = byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte(), ((value shr 16) and 0xff).toByte())

    fun u32le(value: Long): ByteArray = ByteArray(4) { index -> ((value shr (8 * index)) and 0xff).toByte() }

    private inline fun bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()
}
