package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The throughput harness's variant names: its default and every requested name are lanes it can create. */
@RunWith(AndroidJUnit4::class)
class ThroughputVariantTest {
    /** With no argument, the harness runs the reusable baseline around the production domain lane. */
    @Test
    fun noArgumentRunsReuseDomainDomainReuse() {
        val expected = listOf("reuse", "domain", "domain", "reuse")
        assertEquals(expected, ThroughputVariant.parse(null).map { it.key })
        assertEquals(expected, ThroughputVariant.parse(" , ").map { it.key })
    }

    /** Every key round-trips, in the requested order with repeats kept for ABBA passes. */
    @Test
    fun everyKnownKeyIsAccepted() {
        val requested = listOf("single_use", "domain", "reuse", "domain", "single_use")
        assertEquals(requested, ThroughputVariant.parse(requested.joinToString(" , ")).map { it.key })
    }

    /** An unknown name fails the whole request before any pass, naming it and the accepted keys. */
    @Test
    fun anUnknownNameFailsBeforeAnyPass() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) { ThroughputVariant.parse("reuse,per_input") }
        val message = failure.message.orEmpty()
        assertTrue(message, "per_input" in message)
        assertTrue(message, "reuse, single_use, domain" in message)
    }
}
