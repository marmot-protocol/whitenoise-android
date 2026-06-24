package dev.ipf.darkmatter.core

import dev.ipf.darkmatter.core.RecipientSearch.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientSearchTest {
    private fun candidate(
        hex: String,
        name: String,
        npub: String = "npub1$hex",
    ) = Candidate(accountIdHex = hex, displayName = name, npub = npub)

    @Test
    fun matchesDisplayNameCaseInsensitivelyAndTrimmed() {
        val candidates =
            listOf(
                candidate("a".repeat(64), "Alice"),
                candidate("b".repeat(64), "Bob"),
            )
        val matches =
            RecipientSearch.filterByDisplayName(
                query = "  ALI  ",
                candidates = candidates,
                activeAccountIdHex = null,
            )
        assertEquals(listOf("a".repeat(64)), matches.map { it.accountIdHex })
    }

    @Test
    fun matchesSubstringNotJustPrefix() {
        val candidates = listOf(candidate("a".repeat(64), "The Alice Account"))
        val matches =
            RecipientSearch.filterByDisplayName(
                query = "alice",
                candidates = candidates,
                activeAccountIdHex = null,
            )
        assertEquals(listOf("a".repeat(64)), matches.map { it.accountIdHex })
    }

    @Test
    fun ordersPrefixMatchesBeforeContainedMatches() {
        val containedHex = "c".repeat(64)
        val prefixHex = "a".repeat(64)
        // Contained match is listed FIRST in the input; the result must still
        // float the prefix match to the top.
        val candidates =
            listOf(
                candidate(containedHex, "My Alice Friend"),
                candidate(prefixHex, "Alice Smith"),
            )
        val matches =
            RecipientSearch.filterByDisplayName(
                query = "alice",
                candidates = candidates,
                activeAccountIdHex = null,
            )
        assertEquals(listOf(prefixHex, containedHex), matches.map { it.accountIdHex })
    }

    @Test
    fun deDupesByAccountHexKeepingFirst() {
        val hex = "a".repeat(64)
        val candidates =
            listOf(
                candidate(hex, "Alice"),
                candidate(hex.uppercase(), "Alice Duplicate"),
            )
        val matches =
            RecipientSearch.filterByDisplayName(
                query = "alice",
                candidates = candidates,
                activeAccountIdHex = null,
            )
        assertEquals(1, matches.size)
        assertEquals("Alice", matches.single().displayName)
    }

    @Test
    fun excludesActiveAccount() {
        val activeHex = "a".repeat(64)
        val candidates =
            listOf(
                candidate(activeHex, "Alice"),
                candidate("b".repeat(64), "Alicia"),
            )
        val matches =
            RecipientSearch.filterByDisplayName(
                query = "ali",
                candidates = candidates,
                // Active account passed in upper-case to prove the exclusion is
                // case-insensitive.
                activeAccountIdHex = activeHex.uppercase(),
            )
        assertEquals(listOf("b".repeat(64)), matches.map { it.accountIdHex })
    }

    @Test
    fun blankQueryReturnsEmpty() {
        val candidates = listOf(candidate("a".repeat(64), "Alice"))
        assertTrue(
            RecipientSearch
                .filterByDisplayName("   ", candidates, activeAccountIdHex = null)
                .isEmpty(),
        )
    }

    @Test
    fun alreadyInChatsTrueForDirectChat() {
        assertTrue(RecipientSearch.alreadyInChats(hasDirectChat = true, sharedGroupCount = 0))
    }

    @Test
    fun alreadyInChatsTrueForSharedGroup() {
        assertTrue(RecipientSearch.alreadyInChats(hasDirectChat = false, sharedGroupCount = 2))
    }

    @Test
    fun alreadyInChatsFalseWithNoChat() {
        assertFalse(RecipientSearch.alreadyInChats(hasDirectChat = false, sharedGroupCount = 0))
    }
}
