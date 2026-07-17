package dev.ipf.whitenoise.android.ui.qr

import org.junit.Assert.assertEquals
import org.junit.Test

class QrScanResultTest {
    private val validNpub = npub((0 until 32).toList())
    private val invalidNpubChecksum = checksumInvalid(validNpub)
    private val shapeOnlyNpub = "npub1" + "a".repeat(58)
    private val validNsec = nsec((0 until 32).toList())
    private val sampleNprofile = nprofile(listOf(0, 32) + (0 until 32).toList())
    private val invalidNprofileChecksum = checksumInvalid(sampleNprofile)
    private val sampleNprofileHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    @Test
    fun viewProfile_acceptsBareNpub() {
        assertEquals(
            QrScanOutcome.OpenProfileNpub(validNpub),
            QrScanResult.resolve(validNpub, QrScanUseCase.ViewProfile),
        )
    }

    @Test
    fun viewProfile_acceptsNostrPrefixedNpub() {
        assertEquals(
            QrScanOutcome.OpenProfileNpub(validNpub),
            QrScanResult.resolve("nostr:$validNpub", QrScanUseCase.ViewProfile),
        )
        assertEquals(
            QrScanOutcome.OpenProfileNpub(validNpub),
            QrScanResult.resolve("NOSTR:$validNpub", QrScanUseCase.ViewProfile),
        )
    }

    @Test
    fun viewProfile_acceptsProfileLinks() {
        assertEquals(
            QrScanOutcome.OpenProfileNpub(validNpub),
            QrScanResult.resolve("marmot://profile/$validNpub?from=qr", QrScanUseCase.ViewProfile),
        )
    }

    @Test
    fun viewProfile_acceptsBareNprofile() {
        assertEquals(
            QrScanOutcome.OpenProfileNprofile(sampleNprofile, sampleNprofileHex),
            QrScanResult.resolve(sampleNprofile, QrScanUseCase.ViewProfile),
        )
    }

    @Test
    fun viewProfile_acceptsNostrPrefixedNprofile() {
        assertEquals(
            QrScanOutcome.OpenProfileNprofile(sampleNprofile, sampleNprofileHex),
            QrScanResult.resolve("nostr:$sampleNprofile", QrScanUseCase.ViewProfile),
        )
    }

    @Test
    fun viewProfile_acceptsUppercaseBech32Payloads() {
        assertEquals(
            QrScanOutcome.OpenProfileNpub(validNpub),
            QrScanResult.resolve(validNpub.uppercase(), QrScanUseCase.ViewProfile),
        )
        assertEquals(
            QrScanOutcome.OpenProfileNprofile(sampleNprofile, sampleNprofileHex),
            QrScanResult.resolve("NOSTR:${sampleNprofile.uppercase()}", QrScanUseCase.ViewProfile),
        )
    }

    @Test
    fun viewProfile_rejectsMalformedInput() {
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve("", QrScanUseCase.ViewProfile))
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve("npub1abc", QrScanUseCase.ViewProfile))
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve("https://example.com", QrScanUseCase.ViewProfile))
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve(validNsec, QrScanUseCase.ViewProfile))
    }

    @Test
    fun viewProfile_rejectsChecksumInvalidNpubAndNprofile() {
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve(shapeOnlyNpub, QrScanUseCase.ViewProfile))
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve(invalidNpubChecksum, QrScanUseCase.ViewProfile))
        assertEquals(
            QrScanOutcome.Invalid,
            QrScanResult.resolve("marmot://profile/$invalidNpubChecksum?from=qr", QrScanUseCase.ViewProfile),
        )
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve(invalidNprofileChecksum, QrScanUseCase.ViewProfile))
        assertEquals(
            QrScanOutcome.Invalid,
            QrScanResult.resolve("nostr:$invalidNprofileChecksum", QrScanUseCase.ViewProfile),
        )
    }

    @Test
    fun pickRecipient_acceptsNpubProfileLinksAndHex() {
        val hex = "a".repeat(64)
        assertEquals(
            QrScanOutcome.FillRecipientQuery(validNpub),
            QrScanResult.resolve(validNpub, QrScanUseCase.PickRecipient),
        )
        assertEquals(
            QrScanOutcome.FillRecipientQuery(validNpub),
            QrScanResult.resolve("nostr:$validNpub", QrScanUseCase.PickRecipient),
        )
        assertEquals(
            QrScanOutcome.FillRecipientQuery(hex),
            QrScanResult.resolve(hex.uppercase(), QrScanUseCase.PickRecipient),
        )
    }

    @Test
    fun pickRecipient_acceptsNprofileAsHexPubkey() {
        assertEquals(
            QrScanOutcome.FillRecipientQuery(sampleNprofileHex),
            QrScanResult.resolve(sampleNprofile, QrScanUseCase.PickRecipient),
        )
        assertEquals(
            QrScanOutcome.FillRecipientQuery(sampleNprofileHex),
            QrScanResult.resolve("nostr:$sampleNprofile", QrScanUseCase.PickRecipient),
        )
    }

    @Test
    fun pickRecipient_rejectsMalformedInput() {
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve("not-a-key", QrScanUseCase.PickRecipient))
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve(validNsec, QrScanUseCase.PickRecipient))
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve(shapeOnlyNpub, QrScanUseCase.PickRecipient))
        assertEquals(QrScanOutcome.Invalid, QrScanResult.resolve(invalidNpubChecksum, QrScanUseCase.PickRecipient))
    }

    private fun npub(bytes: List<Int>): String = bech32Encode("npub", convertBits(bytes, fromBits = 8, toBits = 5, pad = true))

    private fun nsec(bytes: List<Int>): String = bech32Encode("nsec", convertBits(bytes, fromBits = 8, toBits = 5, pad = true))

    private fun nprofile(bytes: List<Int>): String = bech32Encode("nprofile", convertBits(bytes, fromBits = 8, toBits = 5, pad = true))

    private fun checksumInvalid(encoded: String): String = encoded.dropLast(1) + if (encoded.last() == 'q') 'p' else 'q'

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
