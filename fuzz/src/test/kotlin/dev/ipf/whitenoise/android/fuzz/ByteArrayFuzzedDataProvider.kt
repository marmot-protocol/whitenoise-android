package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider

/** Deterministic [FuzzedDataProvider] backed by a fixed byte array for unit tests. */
internal class ByteArrayFuzzedDataProvider(
    private val data: ByteArray,
) : FuzzedDataProvider {
    private var index = 0

    override fun consumeBoolean(): Boolean = consumeByte().toInt() != 0

    override fun consumeBooleans(size: Int): BooleanArray = BooleanArray(size) { consumeBoolean() }

    override fun consumeByte(): Byte {
        if (index >= data.size) {
            return 0
        }
        return data[index++]
    }

    override fun consumeByte(
        min: Byte,
        max: Byte,
    ): Byte {
        val span = (max - min) and 0xFF
        return (min + ((consumeByte().toInt() and 0xFF) % (span + 1))).toByte()
    }

    override fun consumeBytes(maxLength: Int): ByteArray {
        val length = minOf(maxLength, remainingBytes())
        val slice = data.copyOfRange(index, index + length)
        index += length
        return slice
    }

    override fun consumeRemainingAsBytes(): ByteArray = consumeBytes(remainingBytes())

    override fun consumeShort(): Short = consumeShort(Short.MIN_VALUE, Short.MAX_VALUE)

    override fun consumeShort(
        min: Short,
        max: Short,
    ): Short {
        val span = max - min
        return (min + consumeInt(0, span)).toShort()
    }

    override fun consumeShorts(size: Int): ShortArray = ShortArray(size) { consumeShort() }

    override fun consumeInt(): Int = consumeInt(Int.MIN_VALUE, Int.MAX_VALUE)

    override fun consumeInt(
        min: Int,
        max: Int,
    ): Int {
        if (min >= max) {
            return min
        }
        val span = max.toLong() - min.toLong()
        val offset = (consumeByte().toInt() and 0xFF).toLong() % (span + 1)
        return (min.toLong() + offset).toInt()
    }

    override fun consumeInts(size: Int): IntArray = IntArray(size) { consumeInt() }

    override fun consumeLong(): Long = consumeLong(Long.MIN_VALUE, Long.MAX_VALUE)

    override fun consumeLong(
        min: Long,
        max: Long,
    ): Long {
        if (min >= max) {
            return min
        }
        return min + (consumeByte().toInt() and 0xFF)
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

    override fun consumeAsciiString(maxLength: Int): String = consumeBytes(maxLength).decodeToString(throwOnInvalidSequence = false)

    override fun consumeRemainingAsAsciiString(): String = consumeRemainingAsString()

    override fun remainingBytes(): Int = data.size - index
}
