package dev.ipf.whitenoise.android.ui.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** A sign-in's self-reported app name is shown isolated, without hidden characters and with a capped length. */
class SignInClaimTest {
    /** An ordinary name is shown whole, inside a first-strong isolate. */
    @Test
    fun anOrdinaryNameIsIsolated() {
        assertEquals("\u2068White Noise\u2069", signInClientClaim("White Noise"))
    }

    /** A blank name, or one made only of hidden characters, names nothing. */
    @Test
    fun aBlankNameNamesNothing() {
        assertNull(signInClientClaim(""))
        assertNull(signInClientClaim("  \n\t "))
        assertNull(signInClientClaim("\u202E\u200B\u2066"))
    }

    /** Bidi overrides, isolates and zero-width marks are dropped, so the name cannot reorder the sentence. */
    @Test
    fun bidiControlsAreDropped() {
        val claim = signInClientClaim("White\u202E esioN\u2069\u200B")!!

        assertEquals("\u2068White esioN\u2069", claim)
        listOf('\u202E', '\u200B').forEach { assertFalse(claim.contains(it)) }
        assertEquals(1, claim.count { it == '\u2069' })
    }

    /** Line breaks and runs of spaces become single spaces. */
    @Test
    fun whitespaceIsCollapsed() {
        assertEquals("\u2068White Noise\u2069", signInClientClaim("  White\n\n   Noise  "))
    }

    /** A long name is cut after the cap with an ellipsis. */
    @Test
    fun aLongNameIsCapped() {
        val claim = signInClientClaim("a".repeat(SIGN_IN_CLIENT_MAX_CHARACTERS + 10))!!

        assertEquals("\u2068" + "a".repeat(SIGN_IN_CLIENT_MAX_CHARACTERS) + "\u2026\u2069", claim)
    }

    /** A name exactly at the cap is not cut. */
    @Test
    fun aNameAtTheCapIsWhole() {
        val name = "b".repeat(SIGN_IN_CLIENT_MAX_CHARACTERS)

        assertEquals("\u2068$name\u2069", signInClientClaim(name))
    }

    /** The cap counts characters as people see them, so a surrogate pair is never split. */
    @Test
    fun theCapNeverSplitsACharacter() {
        val emoji = "😀"
        val claim = signInClientClaim(emoji.repeat(SIGN_IN_CLIENT_MAX_CHARACTERS + 1))!!

        assertEquals("\u2068" + emoji.repeat(SIGN_IN_CLIENT_MAX_CHARACTERS) + "\u2026\u2069", claim)
    }
}
