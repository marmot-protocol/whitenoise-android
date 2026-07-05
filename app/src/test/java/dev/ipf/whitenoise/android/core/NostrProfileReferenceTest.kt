package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NostrProfileReferenceTest {
    @Test
    fun extractsPubkeyFromNprofileTlvPayload() {
        val pubkey = (0 until 32).toList()
        val relay = "wss://relay.example".encodeToByteArray().map { it.toInt() and 0xff }
        val nprofile =
            nprofile(
                listOf(1, relay.size) + relay + listOf(0, pubkey.size) + pubkey,
            )

        assertEquals(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            NostrProfileReference.accountIdHex(nprofile),
        )
    }

    @Test
    fun rejectsNonNprofileAndMalformedInputs() {
        val pubkey = List(32) { 0x42 }
        val valid = nprofile(listOf(0, pubkey.size) + pubkey)
        val corrupted = valid.dropLast(1) + if (valid.last() == 'q') 'p' else 'q'
        val missingPubkey = nprofile(listOf(1, 3, 1, 2, 3))
        val shortPubkey = nprofile(listOf(0, 2, 1, 2))
        val mixedCase = valid.replaceFirstChar { it.uppercaseChar() }

        assertNull(NostrProfileReference.accountIdHex("npub1" + "q".repeat(58)))
        assertNull(NostrProfileReference.accountIdHex(corrupted))
        assertNull(NostrProfileReference.accountIdHex(missingPubkey))
        assertNull(NostrProfileReference.accountIdHex(shortPubkey))
        assertNull(NostrProfileReference.accountIdHex(mixedCase))
    }

    private fun nprofile(bytes: List<Int>): String = bech32Encode("nprofile", convertBits(bytes, fromBits = 8, toBits = 5, pad = true))

    private fun bech32Encode(
        hrp: String,
        data: List<Int>,
    ): String {
        val checksum = createChecksum(hrp, data)
        return hrp + "1" + (data + checksum).joinToString("") { BECH32_CHARSET[it].toString() }
    }

    private fun createChecksum(
        hrp: String,
        data: List<Int>,
    ): List<Int> {
        val values = hrpExpand(hrp) + data + List(6) { 0 }
        val polymod = bech32Polymod(values) xor 1
        return (0 until 6).map { i -> (polymod ushr (5 * (5 - i))) and 31 }
    }

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
        if (pad && bits > 0) {
            result += (accumulator shl (toBits - bits)) and maxValue
        }
        return result
    }

    private companion object {
        const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val BECH32_GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    }
}
