package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider

/**
 * Deterministic [FuzzedDataProvider] backed by a fixed byte array for unit tests.
 *
 * Matches Jazzer hybrid consumption: integral primitives are read from the end of the
 * remaining buffer; [consumeBytes] and [consumeRemainingAsBytes] read from the start.
 */
internal class ByteArrayFuzzedDataProvider(
    private val data: ByteArray,
) : FuzzedDataProvider {
    private var startIndex = 0
    private var endExclusive = data.size

    override fun consumeBoolean(): Boolean = (consumeByte().toInt() and 1) != 0

    override fun consumeBooleans(size: Int): BooleanArray = BooleanArray(size) { consumeBoolean() }

    override fun consumeByte(): Byte {
        if (startIndex >= endExclusive) {
            return 0
        }
        return data[--endExclusive]
    }

    override fun consumeByte(
        min: Byte,
        max: Byte,
    ): Byte {
        if (min > max) {
            throw IllegalArgumentException("min must be <= max (got min: $min, max: $max)")
        }
        if (min == max) {
            return min
        }
        val range = max.toULong() - min.toULong()
        val raw = consumeIntegralFromEnd(range, Byte.SIZE_BYTES)
        val direct = raw.toByte()
        if (direct in min..max) {
            return direct
        }
        return (min.toULong() + raw % (range + 1UL)).toByte()
    }

    override fun consumeBytes(maxLength: Int): ByteArray {
        val length = minOf(maxLength, remainingBytes())
        val slice = data.copyOfRange(startIndex, startIndex + length)
        startIndex += length
        return slice
    }

    override fun consumeRemainingAsBytes(): ByteArray = consumeBytes(remainingBytes())

    override fun consumeShort(): Short = consumeShort(Short.MIN_VALUE, Short.MAX_VALUE)

    override fun consumeShort(
        min: Short,
        max: Short,
    ): Short {
        if (min > max) {
            throw IllegalArgumentException("min must be <= max (got min: $min, max: $max)")
        }
        if (min == max) {
            return min
        }
        val range = max.toULong() - min.toULong()
        val raw = consumeIntegralFromEnd(range, Short.SIZE_BYTES)
        val direct = raw.toShort()
        if (direct in min..max) {
            return direct
        }
        return (min.toULong() + raw % (range + 1UL)).toShort()
    }

    override fun consumeShorts(size: Int): ShortArray = ShortArray(size) { consumeShort() }

    override fun consumeInt(): Int = consumeInt(Int.MIN_VALUE, Int.MAX_VALUE)

    override fun consumeInt(
        min: Int,
        max: Int,
    ): Int {
        if (min > max) {
            throw IllegalArgumentException("min must be <= max (got min: $min, max: $max)")
        }
        if (min == max) {
            return min
        }
        val range = max.toULong() - min.toULong()
        val raw = consumeIntegralFromEnd(range, Int.SIZE_BYTES)
        val direct = raw.toInt()
        if (direct in min..max) {
            return direct
        }
        return (min.toULong() + raw % (range + 1UL)).toInt()
    }

    override fun consumeInts(size: Int): IntArray = IntArray(size) { consumeInt() }

    override fun consumeLong(): Long = consumeLong(Long.MIN_VALUE, Long.MAX_VALUE)

    override fun consumeLong(
        min: Long,
        max: Long,
    ): Long {
        if (min > max) {
            throw IllegalArgumentException("min must be <= max (got min: $min, max: $max)")
        }
        if (min == max) {
            return min
        }
        val range = max.toULong() - min.toULong()
        val raw = consumeIntegralFromEnd(range, Long.SIZE_BYTES)
        val direct = raw.toLong()
        if (direct in min..max) {
            return direct
        }
        val offset = if (range == ULong.MAX_VALUE) raw else raw % (range + 1UL)
        return (min.toULong() + offset).toLong()
    }

    override fun consumeLongs(size: Int): LongArray = LongArray(size) { consumeLong() }

    override fun consumeFloat(): Float = Float.fromBits(consumeInt())

    override fun consumeRegularFloat(): Float = consumeRegularFloat(-1f, 1f)

    override fun consumeRegularFloat(
        min: Float,
        max: Float,
    ): Float {
        if (min >= max) {
            return min
        }
        return min + (consumeByte().toInt() and 0xFF) / 255f * (max - min)
    }

    override fun consumeProbabilityFloat(): Float = (consumeByte().toInt() and 0xFF) / 255f

    override fun consumeDouble(): Double = consumeRegularDouble()

    override fun consumeRegularDouble(): Double = consumeRegularDouble(-1.0, 1.0)

    override fun consumeRegularDouble(
        min: Double,
        max: Double,
    ): Double {
        if (min >= max) {
            return min
        }
        return min + (consumeByte().toInt() and 0xFF) / 255.0 * (max - min)
    }

    override fun consumeProbabilityDouble(): Double = (consumeByte().toInt() and 0xFF) / 255.0

    override fun consumeChar(): Char = consumeChar(Char.MIN_VALUE, Char.MAX_VALUE)

    override fun consumeChar(
        min: Char,
        max: Char,
    ): Char {
        if (min >= max) {
            return min
        }
        return (min.code + (consumeByte().toInt() and 0xFF) % (max.code - min.code + 1)).toChar()
    }

    override fun consumeCharNoSurrogates(): Char = consumeChar(' ', '~')

    override fun consumeString(maxLength: Int): String = consumeBytes(maxLength).decodeToString(throwOnInvalidSequence = false)

    override fun consumeRemainingAsString(): String = consumeRemainingAsBytes().decodeToString(throwOnInvalidSequence = false)

    override fun consumeAsciiString(maxLength: Int): String = consumeBytes(maxLength).decodeTo7BitAscii()

    override fun consumeRemainingAsAsciiString(): String = consumeRemainingAsBytes().decodeTo7BitAscii()

    override fun remainingBytes(): Int = endExclusive - startIndex

    private fun consumeIntegralFromEnd(
        range: ULong,
        maxBytes: Int,
    ): ULong {
        var value = 0UL
        var bits = 0
        while (bits < maxBytes * Byte.SIZE_BITS && (range shr bits) > 0UL && startIndex < endExclusive) {
            value = (value shl 8) or data[--endExclusive].toUByte().toULong()
            bits += 8
        }
        return value
    }

    private fun ByteArray.decodeTo7BitAscii(): String =
        buildString(size) {
            for (byte in this@decodeTo7BitAscii) {
                append((byte.toInt() and 0x7F).toChar())
            }
        }
}
