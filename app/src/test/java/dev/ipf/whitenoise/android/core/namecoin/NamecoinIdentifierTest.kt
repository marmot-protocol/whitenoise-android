package dev.ipf.whitenoise.android.core.namecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identifier gate + parsing tests for [NamecoinResolver.isBit] and
 * [NamecoinResolver.parse], ported from the identifier sections of
 * `tests/app/namecoin_test.odin` (whitenoise-linux commit 79447095f2).
 */
class NamecoinIdentifierTest {
    // ── identifier gate ────────────────────────────────────────────────

    @Test
    fun isBitPositives() {
        assertTrue(NamecoinResolver.isBit("alice@example.bit"))
        assertTrue(NamecoinResolver.isBit("Alice@Example.BIT"))
        assertTrue(NamecoinResolver.isBit("example.bit"))
        assertTrue(NamecoinResolver.isBit("nostr:example.bit"))
        assertTrue(NamecoinResolver.isBit("d/example"))
        assertTrue(NamecoinResolver.isBit("id/alice"))
        assertTrue(NamecoinResolver.isBit("  d/example  "))
    }

    @Test
    fun isBitNegatives() {
        assertTrue(!NamecoinResolver.isBit(""))
        assertTrue(!NamecoinResolver.isBit(".bit"))
        assertTrue(!NamecoinResolver.isBit("d/"))
        assertTrue(!NamecoinResolver.isBit("id/"))
        assertTrue(!NamecoinResolver.isBit("example.com"))
        assertTrue(!NamecoinResolver.isBit("alice@example.com"))
        assertTrue(
            !NamecoinResolver.isBit(
                "npub1w90qcnzy9pxxaanl7zt50pgn8gj9tnpwd3wkfnq8fj0h4pft2eus5at3v3",
            ),
        )
    }

    // ── identifier parsing ─────────────────────────────────────────────

    @Test
    fun parseBareDomain() {
        val parsed = NamecoinResolver.parse("Example.BIT")
        assertNotNull(parsed)
        assertEquals("d/example", parsed!!.namecoinName)
        assertEquals("_", parsed.localPart)
        assertTrue(parsed.isDomain)
    }

    @Test
    fun parseUserAtDomain() {
        val parsed = NamecoinResolver.parse("Alice@Example.bit")
        assertNotNull(parsed)
        assertEquals("d/example", parsed!!.namecoinName)
        assertEquals("alice", parsed.localPart)
        assertTrue(parsed.isDomain)
    }

    @Test
    fun parseEmptyLocalPartFallsBackToRoot() {
        val parsed = NamecoinResolver.parse("@example.bit")
        assertNotNull(parsed)
        assertEquals("_", parsed!!.localPart)
    }

    @Test
    fun parseDSlash() {
        val parsed = NamecoinResolver.parse("d/Foo")
        assertNotNull(parsed)
        assertEquals("d/foo", parsed!!.namecoinName)
        assertEquals("_", parsed.localPart)
        assertTrue(parsed.isDomain)
    }

    @Test
    fun parseIdSlash() {
        val parsed = NamecoinResolver.parse("id/Alice")
        assertNotNull(parsed)
        assertEquals("id/alice", parsed!!.namecoinName)
        assertEquals("_", parsed.localPart)
        assertTrue(!parsed.isDomain)
    }

    @Test
    fun parseNostrPrefix() {
        val parsed = NamecoinResolver.parse("nostr:example.bit")
        assertNotNull(parsed)
        assertEquals("d/example", parsed!!.namecoinName)
    }

    @Test
    fun parseRejectsNonBit() {
        assertNull(NamecoinResolver.parse("example.com"))
        assertNull(NamecoinResolver.parse("d/"))
        assertNull(NamecoinResolver.parse(""))
    }
}
