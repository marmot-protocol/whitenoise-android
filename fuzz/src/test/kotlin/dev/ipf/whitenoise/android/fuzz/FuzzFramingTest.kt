package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import dev.ipf.whitenoise.android.amber.signedEventPubkeyMismatchReason
import dev.ipf.whitenoise.android.core.RecipientReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuzzFramingTest {
    @Test
    fun consumeFramedString_legacySeedConsumesEntirePayload() {
        val payload = """{"pubkey":"abc","id":"deadbeef","sig":"abc"}"""
        val provider = providerFromBytes(payload.encodeToByteArray())
        val framed = provider.consumeFramedString()
        assertEquals(payload, framed.value)
        assertTrue(framed.consumedAllRemaining)
        assertEquals(0, provider.remainingBytes())
    }

    @Test
    fun consumeFramedString_structuredFieldsLeaveRemainingBytes() {
        val first = "event"
        val second = "pubkey"
        val bytes =
            byteArrayOf(
                first.length.toByte(),
                *first.encodeToByteArray(),
                second.length.toByte(),
                *second.encodeToByteArray(),
            )
        val provider = providerFromBytes(bytes)
        val firstField = provider.consumeFramedString()
        assertEquals(first, firstField.value)
        assertFalse(firstField.consumedAllRemaining)
        val secondField = provider.consumeFramedString()
        assertEquals(second, secondField.value)
        assertTrue(secondField.consumedAllRemaining)
    }

    @Test
    fun signedEventPubkeyHelpers_exercisesMismatchBranch() {
        val eventJson = """{"pubkey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        val expectedPubkey = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val bytes =
            byteArrayOf(
                eventJson.length.toByte(),
                *eventJson.encodeToByteArray(),
                expectedPubkey.length.toByte(),
                *expectedPubkey.encodeToByteArray(),
            )
        val provider = providerFromBytes(bytes)
        val eventField = provider.consumeFramedString()
        val expectedField = provider.consumeFramedString()
        assertNotNull(signedEventPubkeyMismatchReason(eventField.value, expectedField.value))
    }

    @Test
    fun plausibleClipboard_allowHexBranchReachesHexNormalization() {
        val hexKey = "a".repeat(64)
        val provider =
            providerFromBytes(
                byteArrayOf(
                    hexKey.length.toByte(),
                    *hexKey.encodeToByteArray(),
                    1,
                    0,
                ),
            )
        val rawField = provider.consumeFramedString()
        assertFalse(rawField.consumedAllRemaining)
        val allowHex = provider.consumeBoolean()
        val nullRaw = provider.consumeBoolean()
        assertTrue(allowHex)
        assertFalse(nullRaw)
        val plausible = RecipientReference.plausibleClipboardInput(rawField.value, allowHexPublicKey = allowHex)
        assertEquals(hexKey, plausible)
    }
}

private fun providerFromBytes(bytes: ByteArray): FuzzedDataProvider = ByteArrayFuzzedDataProvider(bytes)
