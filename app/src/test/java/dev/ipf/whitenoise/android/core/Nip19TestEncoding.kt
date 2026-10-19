package dev.ipf.whitenoise.android.core

// Checksummed NIP-19 strings for tests, so fixture references decode exactly as authored ones do.

private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val BECH32_GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

/** Bech32-encodes [bytes] under [hrp], the way NIP-19 encodes `note`, `nevent` and `naddr` values. */
internal fun nip19(
    hrp: String,
    bytes: List<Int>,
): String {
    val data = convertBits(bytes, fromBits = 8, toBits = 5, pad = true)
    val checksum = createChecksum(hrp, data)
    return hrp + "1" + (data + checksum).joinToString("") { BECH32_CHARSET[it].toString() }
}

/** One NIP-19 TLV entry of [type] holding [value]. */
internal fun nip19Tlv(
    type: Int,
    value: List<Int>,
): List<Int> = listOf(type, value.size) + value

/** [value] as the four big-endian bytes of a NIP-19 kind entry. */
internal fun nip19Uint(value: UInt): List<Int> =
    listOf(
        (value shr 24).toInt() and 0xff,
        (value shr 16).toInt() and 0xff,
        (value shr 8).toInt() and 0xff,
        value.toInt() and 0xff,
    )

/** The UTF-8 bytes of [this] as unsigned ints. */
internal fun String.utf8Ints(): List<Int> = encodeToByteArray().map { it.toInt() and 0xff }

/** Lowercase hex of [this] byte list. */
internal fun List<Int>.hexString(): String = joinToString("") { "%02x".format(it) }

/** The bech32 checksum of [data] under [hrp]. */
private fun createChecksum(
    hrp: String,
    data: List<Int>,
): List<Int> {
    val values = hrpExpand(hrp) + data + List(6) { 0 }
    val polymod = bech32Polymod(values) xor 1
    return (0 until 6).map { i -> (polymod ushr (5 * (5 - i))) and 31 }
}

/** The human-readable part expanded for checksumming. */
private fun hrpExpand(hrp: String): List<Int> = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

/** The bech32 BCH checksum polynomial over [values]. */
private fun bech32Polymod(values: List<Int>): Int {
    var checksum = 1
    for (value in values) {
        val top = checksum ushr 25
        checksum = ((checksum and 0x1ffffff) shl 5) xor value
        for (i in BECH32_GENERATORS.indices) {
            if (((top ushr i) and 1) != 0) checksum = checksum xor BECH32_GENERATORS[i]
        }
    }
    return checksum
}

/** Regroups [values] from [fromBits]-bit to [toBits]-bit words, padding the tail when [pad] is set. */
private fun convertBits(
    values: List<Int>,
    fromBits: Int,
    toBits: Int,
    pad: Boolean,
): List<Int> {
    var accumulator = 0
    var bits = 0
    val maxValue = (1 shl toBits) - 1
    val maxAccumulator = (1 shl (fromBits + toBits - 1)) - 1
    val result = mutableListOf<Int>()
    for (value in values) {
        accumulator = ((accumulator shl fromBits) or value) and maxAccumulator
        bits += fromBits
        while (bits >= toBits) {
            bits -= toBits
            result += (accumulator ushr bits) and maxValue
        }
    }
    if (pad && bits > 0) result += (accumulator shl (toBits - bits)) and maxValue
    return result
}
