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
        val provider = providerFromBytes(payload.encodeToByteArray() + byteArrayOf(1))
        val framed = provider.consumeDirectOrFramedString()
        assertEquals(payload, framed.value)
        assertTrue(framed.consumedAllRemaining)
        assertEquals(0, provider.remainingBytes())
    }

    @Test
    fun consumeFramedString_longLegacyJsonConsumesEntirePayload() {
        val payload =
            buildString {
                append('{')
                repeat(150) {
                    append('a')
                }
                append('}')
            }
        val provider = providerFromBytes(payload.encodeToByteArray() + byteArrayOf(1))
        val framed = provider.consumeDirectOrFramedString()
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
                *first.encodeToByteArray(),
                *second.encodeToByteArray(),
                second.length.toByte(),
                first.length.toByte(),
                0,
            )
        val provider = providerFromBytes(bytes)
        val firstField = provider.consumeDirectOrFramedString()
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
                *eventJson.encodeToByteArray(),
                *expectedPubkey.encodeToByteArray(),
                expectedPubkey.length.toByte(),
                eventJson.length.toByte(),
                0,
            )
        val provider = providerFromBytes(bytes)
        val eventField = provider.consumeDirectOrFramedString()
        val expectedField = provider.consumeFramedString()
        assertNotNull(signedEventPubkeyMismatchReason(eventField.value, expectedField.value))
    }

    @Test
    fun plausibleClipboard_allowHexBranchReachesHexNormalization() {
        val hexKey = "a".repeat(64)
        val provider =
            providerFromBytes(
                byteArrayOf(
                    *hexKey.encodeToByteArray(),
                    0xFF.toByte(),
                    0,
                ),
            )
        val rawField = provider.consumeDirectOrFramedString()
        assertEquals(hexKey, rawField.value)
        val plausible = RecipientReference.plausibleClipboardInput(rawField.value, allowHexPublicKey = true)
        assertEquals(hexKey, plausible)
    }
}

private fun providerFromBytes(bytes: ByteArray): FuzzedDataProvider = ByteArrayFuzzedDataProvider(bytes)
