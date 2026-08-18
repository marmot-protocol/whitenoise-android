package dev.ipf.whitenoise.android.fuzz

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ByteArrayFuzzedDataProviderTest {
    @Test
    fun consumeIntSupportsTheFullIntRange() {
        val provider = ByteArrayFuzzedDataProvider(byteArrayOf(0))

        assertEquals(Int.MIN_VALUE, provider.consumeInt())
    }
}
