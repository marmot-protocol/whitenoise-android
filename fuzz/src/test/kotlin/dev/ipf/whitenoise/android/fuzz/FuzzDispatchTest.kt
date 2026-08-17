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
    fun consumeSubtarget_mapsEveryByteEvenly() {
        val counts = IntArray(ZapstoreSubtarget.COUNT)
        for (byte in 0..255) {
            val id = subtargetIdFromByte(byte, ZapstoreSubtarget.COUNT)
            counts[id]++
        }
        val expectedPerBucket = 256 / ZapstoreSubtarget.COUNT
        val remainder = 256 % ZapstoreSubtarget.COUNT
        counts.forEachIndexed { index, observed ->
            val expected = expectedPerBucket + if (index < remainder) 1 else 0
            assertEquals(expected, observed, "byte bucket $index")
        }
    }
}

/** Mirrors [FuzzedDataProvider.consumeSubtarget] without needing a live fuzz input. */
internal fun subtargetIdFromByte(
    byte: Int,
    count: Int,
): Int = (byte and 0xFF) % count
