package dev.ipf.whitenoise.android.fuzz

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FuzzDispatchTest {
    @Test
    fun subtargetFromId_mapsWithModulo() {
        assertEquals(ZapstoreSubtarget.NostrEventJson, ZapstoreSubtarget.fromId(0))
        assertEquals(ZapstoreSubtarget.RelayEnvelopeSequence, ZapstoreSubtarget.fromId(2))
        assertEquals(ZapstoreSubtarget.NostrEventJson, ZapstoreSubtarget.fromId(3))
        assertEquals(ZapstoreSubtarget.RelayEnvelopeFrames, ZapstoreSubtarget.fromId(253))
    }

    @Test
    fun consumeSubtarget_readsSelectorFromEndOfInput() {
        val payload = byteArrayOf('e'.code.toByte(), 'v'.code.toByte(), 't'.code.toByte())
        val provider = ByteArrayFuzzedDataProvider(payload + byteArrayOf(4))
        assertEquals(1, provider.consumeSubtarget(ZapstoreSubtarget.COUNT))
        assertEquals(3, provider.remainingBytes())
    }

    @Test
    fun consumeSubtarget_mapsEverySelectorByte() {
        val counts = IntArray(ZapstoreSubtarget.COUNT)
        for (selector in 0..255) {
            val provider = ByteArrayFuzzedDataProvider(byteArrayOf(selector.toByte()))
            val id = provider.consumeSubtarget(ZapstoreSubtarget.COUNT)
            counts[id]++
        }
        val expectedPerBucket = 256 / ZapstoreSubtarget.COUNT
        val remainder = 256 % ZapstoreSubtarget.COUNT
        counts.forEachIndexed { index, observed ->
            val expected = expectedPerBucket + if (index < remainder) 1 else 0
            assertEquals(expected, observed, "selector bucket $index")
        }
    }
}
