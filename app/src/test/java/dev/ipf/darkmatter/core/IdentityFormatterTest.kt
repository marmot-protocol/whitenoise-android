package dev.ipf.darkmatter.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityFormatterTest {
    @Test
    fun futureTimestampUsesExplicitLabel() {
        val tomorrow = (Instant.now().epochSecond + 86_400L).toULong()

        assertEquals("future", IdentityFormatter.relativeTime(tomorrow))
    }
}
