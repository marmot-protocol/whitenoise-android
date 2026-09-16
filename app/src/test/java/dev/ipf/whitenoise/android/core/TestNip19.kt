package dev.ipf.whitenoise.android.core

/**
 * Test-only NIP-19 `npub` decoder. Production decodes profile references through MarmotKit since
 * 0.10.0; JVM tests cannot load the native library, so screens that validate scanned or pasted
 * references script this decoder through the app state's reference-resolver seam.
 */
internal object TestNip19 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    private const val CHECKSUM_LENGTH = 6
    private const val PUBKEY_BYTES = 32

    /** Hex pubkey for a checksum-valid lower- or upper-case `npub`, or null for anything else. */
    fun npubToHex(reference: String): String? {
        val candidate = reference.trim().removePrefix("nostr:").removePrefix("NOSTR:")
        val singleCase = candidate == candidate.lowercase() || candidate == candidate.uppercase()
        val lower = candidate.lowercase()
        val data = lower.removePrefix("npub1").map { CHARSET.indexOf(it) }
        val wellFormed = singleCase && lower.startsWith("npub1") && data.none { it < 0 } && data.size > CHECKSUM_LENGTH
        val bytes =
            if (wellFormed && polymod(expandHrp("npub") + data) == 1) {
                convertBits(data.dropLast(CHECKSUM_LENGTH), fromBits = 5, toBits = 8)
            } else {
                null
            }
        return bytes?.takeIf { it.size == PUBKEY_BYTES }?.joinToString("") { "%02x".format(it) }
    }

    private fun expandHrp(hrp: String): List<Int> = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in GENERATORS.indices) {
                if (((top ushr i) and 1) != 0) checksum = checksum xor GENERATORS[i]
            }
        }
        return checksum
    }

    private fun convertBits(
        values: List<Int>,
        fromBits: Int,
        toBits: Int,
    ): List<Int>? {
        var accumulator = 0
        var bits = 0
        val maxValue = (1 shl toBits) - 1
        val result = mutableListOf<Int>()
        for (value in values) {
            accumulator = (accumulator shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result += (accumulator ushr bits) and maxValue
            }
        }
        if (bits >= fromBits || ((accumulator shl (toBits - bits)) and maxValue) != 0) return null
        return result
    }
}
