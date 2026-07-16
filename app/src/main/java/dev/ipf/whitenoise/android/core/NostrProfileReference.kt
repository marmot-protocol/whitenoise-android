package dev.ipf.whitenoise.android.core

/**
 * Minimal NIP-19 Bech32 decoder for profile mentions and QR scan validation.
 *
 * Rust's current `accountIdHex` FFI helper normalizes npub/hex but not nprofile
 * TLVs. Android needs the embedded type-0 pubkey so pasted nprofile mentions can
 * use the same profile-cache and roster-membership paths as npub mentions (#1017),
 * and so QR scans can reject checksum-invalid npub/nprofile payloads without
 * duplicating a second Bech32 implementation. Relay TLVs are deliberately
 * ignored here; profile fetching still flows through the app's existing
 * relay/profile cache machinery.
 */
internal object NostrProfileReference {
    private const val HRP_NPUB = "npub"
    private const val HRP_NPROFILE = "nprofile"
    private const val TLV_PUBKEY = 0
    private const val PUBKEY_BYTES = 32
    private const val BECH32_CHECKSUM_VALUES = 6
    private const val BECH32_SEPARATOR = '1'
    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val BECH32_GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun accountIdHex(reference: String): String? {
        val decoded = decodeBech32(reference.trim()) ?: return null
        if (decoded.hrp != HRP_NPROFILE) return null
        val payload = convertBits(decoded.data, fromBits = 5, toBits = 8, pad = false) ?: return null
        return firstPubkeyTlvHex(payload)
    }

    fun isValidNpub(reference: String): Boolean {
        val decoded = decodeBech32(reference.trim()) ?: return false
        if (decoded.hrp != HRP_NPUB) return false
        return pubkeyHex(decoded.data) != null
    }

    private fun pubkeyHex(data: List<Int>): String? {
        val payload = convertBits(data, fromBits = 5, toBits = 8, pad = false) ?: return null
        if (payload.size != PUBKEY_BYTES) return null
        return payload.toHexString()
    }

    private fun firstPubkeyTlvHex(payload: List<Int>): String? {
        var offset = 0
        var pubkeyHex: String? = null
        while (offset < payload.size) {
            if (offset + 2 > payload.size) return null
            val type = payload[offset]
            val length = payload[offset + 1]
            offset += 2
            if (offset + length > payload.size) return null
            if (type == TLV_PUBKEY) {
                if (length != PUBKEY_BYTES || pubkeyHex != null) return null
                pubkeyHex = payload.subList(offset, offset + PUBKEY_BYTES).toHexString()
            }
            offset += length
        }
        return pubkeyHex
    }

    private data class Bech32Decoded(
        val hrp: String,
        val data: List<Int>,
    )

    private fun decodeBech32(raw: String): Bech32Decoded? {
        if (raw.isEmpty()) return null
        if (raw.any { it.code < 33 || it.code > 126 }) return null
        val hasLower = raw.any { it in 'a'..'z' }
        val hasUpper = raw.any { it in 'A'..'Z' }
        if (hasLower && hasUpper) return null

        val normalized = raw.lowercase()
        val separator = normalized.lastIndexOf(BECH32_SEPARATOR)
        if (separator < 1) return null
        if (separator + 1 + BECH32_CHECKSUM_VALUES > normalized.length) return null

        val hrp = normalized.substring(0, separator)
        val values =
            normalized
                .substring(separator + 1)
                .map { BECH32_CHARSET.indexOf(it) }
                .takeIf { values -> values.all { it >= 0 } }
                ?: return null
        if (!verifyChecksum(hrp, values)) return null
        return Bech32Decoded(hrp = hrp, data = values.dropLast(BECH32_CHECKSUM_VALUES))
    }

    private fun verifyChecksum(
        hrp: String,
        values: List<Int>,
    ): Boolean = bech32Polymod(hrpExpand(hrp) + values) == 1

    private fun hrpExpand(hrp: String): List<Int> = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun bech32Polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in BECH32_GENERATORS.indices) {
                if (((top ushr i) and 1) != 0) {
                    checksum = checksum xor BECH32_GENERATORS[i]
                }
            }
        }
        return checksum
    }

    private fun convertBits(
        values: List<Int>,
        fromBits: Int,
        toBits: Int,
        pad: Boolean,
    ): List<Int>? {
        var accumulator = 0
        var bits = 0
        val maxValue = (1 shl toBits) - 1
        val maxAccumulator = (1 shl (fromBits + toBits - 1)) - 1
        val result = mutableListOf<Int>()
        for (value in values) {
            if (value < 0 || (value ushr fromBits) != 0) return null
            accumulator = ((accumulator shl fromBits) or value) and maxAccumulator
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result += (accumulator ushr bits) and maxValue
            }
        }
        if (pad) {
            if (bits > 0) result += (accumulator shl (toBits - bits)) and maxValue
        } else {
            if (bits >= fromBits) return null
            if (((accumulator shl (toBits - bits)) and maxValue) != 0) return null
        }
        return result
    }

    private fun List<Int>.toHexString(): String =
        buildString(size * 2) {
            for (byte in this@toHexString) {
                append(HEX_CHARS[(byte ushr 4) and 0x0f])
                append(HEX_CHARS[byte and 0x0f])
            }
        }
}
