package dev.ipf.whitenoise.android.core

internal data class NostrBech32Decoded(
    val hrp: String,
    val data: List<Int>,
)

/** Minimal Bech32 codec shared by the temporary Android-side NIP-19 adapters. */
internal object NostrBech32Codec {
    fun decode(raw: String): NostrBech32Decoded? =
        raw
            .takeIf { it.isValidBech32Input() }
            ?.lowercase()
            ?.let(::splitAndDecode)

    fun convertBits(
        values: List<Int>,
        fromBits: Int,
        toBits: Int,
        pad: Boolean,
    ): List<Int>? {
        var accumulator = 0
        var bitCount = 0
        var valid = true
        val maxValue = (1 shl toBits) - 1
        val maxAccumulator = (1 shl (fromBits + toBits - 1)) - 1
        val result = mutableListOf<Int>()
        for (value in values) {
            if (value < 0 || (value ushr fromBits) != 0) {
                valid = false
                break
            }
            accumulator = ((accumulator shl fromBits) or value) and maxAccumulator
            bitCount += fromBits
            while (bitCount >= toBits) {
                bitCount -= toBits
                result += (accumulator ushr bitCount) and maxValue
            }
        }
        if (valid && pad && bitCount > 0) {
            result += (accumulator shl (toBits - bitCount)) and maxValue
        }
        val validRemainder =
            pad ||
                (bitCount < fromBits && ((accumulator shl (toBits - bitCount)) and maxValue) == 0)
        return result.takeIf { valid && validRemainder }
    }

    private fun String.isValidBech32Input(): Boolean {
        val validLength = isNotEmpty() && length <= MAX_NIP19_LENGTH
        val printable = all { it.code in MIN_PRINTABLE_ASCII..MAX_PRINTABLE_ASCII }
        val mixedCase = any { it in 'a'..'z' } && any { it in 'A'..'Z' }
        return validLength && printable && !mixedCase
    }

    private fun splitAndDecode(normalized: String): NostrBech32Decoded? {
        val separator = normalized.lastIndexOf(BECH32_SEPARATOR)
        val hasPayload = separator >= 1 && separator + 1 + BECH32_CHECKSUM_VALUES <= normalized.length
        val hrp = normalized.substring(0, separator.coerceAtLeast(0)).takeIf { hasPayload }
        val values =
            normalized
                .substring((separator + 1).coerceAtLeast(0))
                .map { BECH32_CHARSET.indexOf(it) }
                .takeIf { hasPayload && it.all { value -> value >= 0 } }
        return if (hrp != null && values != null && verifyChecksum(hrp, values)) {
            NostrBech32Decoded(hrp, values.dropLast(BECH32_CHECKSUM_VALUES))
        } else {
            null
        }
    }

    private fun verifyChecksum(
        hrp: String,
        values: List<Int>,
    ): Boolean = bech32Polymod(hrpExpand(hrp) + values) == 1

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code shr BECH32_RADIX_BITS } +
            listOf(0) +
            hrp.map { it.code and BECH32_LOW_BITS_MASK }

    private fun bech32Polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr BECH32_POLYMOD_SHIFT
            checksum = ((checksum and BECH32_CHECKSUM_MASK) shl BECH32_RADIX_BITS) xor value
            for (index in BECH32_GENERATORS.indices) {
                if (((top ushr index) and 1) != 0) {
                    checksum = checksum xor BECH32_GENERATORS[index]
                }
            }
        }
        return checksum
    }

    private const val MAX_NIP19_LENGTH = 5_000
    private const val MIN_PRINTABLE_ASCII = 33
    private const val MAX_PRINTABLE_ASCII = 126
    private const val BECH32_CHECKSUM_VALUES = 6
    private const val BECH32_RADIX_BITS = 5
    private const val BECH32_LOW_BITS_MASK = 31
    private const val BECH32_POLYMOD_SHIFT = 25
    private const val BECH32_CHECKSUM_MASK = 0x1ffffff
    private const val BECH32_SEPARATOR = '1'
    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val BECH32_GENERATORS =
        intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
}
