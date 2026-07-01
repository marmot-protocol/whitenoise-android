package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.core.RecipientSearch.Candidate
import org.junit.Assert.assertEquals
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
    fun browseWithBlankQueryReturnsAllCandidatesInInputOrder() {
        // The caller pre-sorts by recency, so browse must preserve input order
        // rather than re-sorting alphabetically.
        val candidates =
            listOf(
                candidate("b".repeat(64), "Zoe"),
                candidate("a".repeat(64), "Alice"),
                candidate("c".repeat(64), "Mallory"),
            )
        val browsed = RecipientSearch.browse("  ", candidates, activeAccountIdHex = null)
        assertEquals(
            listOf("b".repeat(64), "a".repeat(64), "c".repeat(64)),
            browsed.map { it.accountIdHex },
        )
    }

    @Test
    fun browseWithBlankQueryDeDupesAndExcludesActiveAccount() {
        val activeHex = "a".repeat(64)
        val dupHex = "b".repeat(64)
        val candidates =
            listOf(
                candidate(activeHex, "Me"),
                candidate(dupHex, "Bob"),
                candidate(dupHex.uppercase(), "Bob Again"),
            )
        val browsed =
            RecipientSearch.browse("", candidates, activeAccountIdHex = activeHex.uppercase())
        assertEquals(1, browsed.size)
        assertEquals("Bob", browsed.single().displayName)
    }

    @Test
    fun browseExcludesListedAccounts() {
        val excludedHex = "b".repeat(64)
        val candidates =
            listOf(
                candidate("a".repeat(64), "Alice"),
                candidate(excludedHex, "Bob"),
            )
        val browsed =
            RecipientSearch.browse(
                query = "",
                candidates = candidates,
                activeAccountIdHex = null,
                // Excluded set passed upper-case to prove the match is
                // case-insensitive, mirroring the active-account exclusion.
                excludeAccountIdHexes = setOf(excludedHex.uppercase()),
            )
        assertEquals(listOf("a".repeat(64)), browsed.map { it.accountIdHex })
    }

    @Test
    fun browseWithQueryFiltersInPlacePrefixFirst() {
        val containedHex = "c".repeat(64)
        val prefixHex = "a".repeat(64)
        val candidates =
            listOf(
                candidate(containedHex, "My Alice Friend"),
                candidate(prefixHex, "Alice Smith"),
                candidate("b".repeat(64), "Bob"),
            )
        val browsed = RecipientSearch.browse("alice", candidates, activeAccountIdHex = null)
        assertEquals(listOf(prefixHex, containedHex), browsed.map { it.accountIdHex })
    }
}
