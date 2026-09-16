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

    private fun viewProfile(raw: String): QrScanOutcome {
        val outcome = QrScanResult.resolve(raw, QrScanUseCase.ViewProfile, ::scriptedAccountIdHex)
        return outcome
    }

    private fun pickRecipient(raw: String): QrScanOutcome {
        val outcome = QrScanResult.resolve(raw, QrScanUseCase.PickRecipient, ::scriptedAccountIdHex)
        return outcome
    }

    /** Stands in for MarmotKit's decoder: only the two checksum-valid fixtures resolve, case-insensitively. */
    private fun scriptedAccountIdHex(reference: String): String? =
        when (reference.lowercase()) {
            validNpub -> sampleNprofileHex
            sampleNprofile -> sampleNprofileHex
            else -> null
        }

    @Test
    fun viewProfile_acceptsBareNpub() {
        assertEquals(
            QrScanOutcome.OpenProfileNpub(validNpub),
            viewProfile(validNpub),
        )
    }

    @Test
    fun viewProfile_acceptsNostrPrefixedNpub() {
        assertEquals(
            QrScanOutcome.OpenProfileNpub(validNpub),
            viewProfile("nostr:$validNpub"),
        )
        assertEquals(
            QrScanOutcome.OpenProfileNpub(validNpub),
            viewProfile("NOSTR:$validNpub"),
        )
    }

    @Test
    fun viewProfile_acceptsProfileLinks() {
        assertEquals(
            QrScanOutcome.OpenProfileNpub(validNpub),
            viewProfile("marmot://profile/$validNpub?from=qr"),
        )
    }

    @Test
    fun viewProfile_acceptsBareNprofile() {
        assertEquals(
            QrScanOutcome.OpenProfileNprofile(sampleNprofile, sampleNprofileHex),
            viewProfile(sampleNprofile),
        )
    }

    @Test
    fun viewProfile_acceptsNostrPrefixedNprofile() {
        assertEquals(
            QrScanOutcome.OpenProfileNprofile(sampleNprofile, sampleNprofileHex),
            viewProfile("nostr:$sampleNprofile"),
        )
    }

    @Test
    fun viewProfile_acceptsUppercaseBech32Payloads() {
        assertEquals(
            QrScanOutcome.OpenProfileNpub(validNpub),
            viewProfile(validNpub.uppercase()),
        )
        assertEquals(
            QrScanOutcome.OpenProfileNprofile(sampleNprofile, sampleNprofileHex),
            viewProfile("NOSTR:${sampleNprofile.uppercase()}"),
        )
    }

    @Test
    fun viewProfile_rejectsMalformedInput() {
        assertEquals(QrScanOutcome.Invalid, viewProfile(""))
        assertEquals(QrScanOutcome.Invalid, viewProfile("npub1abc"))
        assertEquals(QrScanOutcome.Invalid, viewProfile("https://example.com"))
        assertEquals(QrScanOutcome.Invalid, viewProfile(validNsec))
    }

    @Test
    fun viewProfile_rejectsChecksumInvalidNpubAndNprofile() {
        assertEquals(QrScanOutcome.Invalid, viewProfile(shapeOnlyNpub))
        assertEquals(QrScanOutcome.Invalid, viewProfile(invalidNpubChecksum))
        assertEquals(
            QrScanOutcome.Invalid,
            viewProfile("marmot://profile/$invalidNpubChecksum?from=qr"),
        )
        assertEquals(QrScanOutcome.Invalid, viewProfile(invalidNprofileChecksum))
        assertEquals(
            QrScanOutcome.Invalid,
            viewProfile("nostr:$invalidNprofileChecksum"),
        )
    }

    @Test
    fun pickRecipient_acceptsNpubProfileLinksAndHex() {
        val hex = "a".repeat(64)
        assertEquals(
            QrScanOutcome.FillRecipientQuery(validNpub),
            pickRecipient(validNpub),
        )
        assertEquals(
            QrScanOutcome.FillRecipientQuery(validNpub),
            pickRecipient("nostr:$validNpub"),
        )
        assertEquals(
            QrScanOutcome.FillRecipientQuery(hex),
            pickRecipient(hex.uppercase()),
        )
    }

    @Test
    fun pickRecipient_acceptsNprofileAsHexPubkey() {
        assertEquals(
            QrScanOutcome.FillRecipientQuery(sampleNprofileHex),
            pickRecipient(sampleNprofile),
        )
        assertEquals(
            QrScanOutcome.FillRecipientQuery(sampleNprofileHex),
            pickRecipient("nostr:$sampleNprofile"),
        )
    }

    @Test
    fun pickRecipient_rejectsMalformedInput() {
        assertEquals(QrScanOutcome.Invalid, pickRecipient("not-a-key"))
        assertEquals(QrScanOutcome.Invalid, pickRecipient(validNsec))
        assertEquals(QrScanOutcome.Invalid, pickRecipient(shapeOnlyNpub))
        assertEquals(QrScanOutcome.Invalid, pickRecipient(invalidNpubChecksum))
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
