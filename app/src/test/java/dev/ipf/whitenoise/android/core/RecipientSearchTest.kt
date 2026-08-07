package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MatchQualityFfi
import dev.ipf.marmotkit.MatchedFieldFfi
import dev.ipf.marmotkit.UserDirectorySearchResultFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
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

    @Test
    fun browseMatchesDiscoveryProfileNip05AndDiacritics() {
        val hex = "d".repeat(64)
        val discovered =
            candidate(hex, "Fallback").copy(
                searchProfile = profile(displayName = "Jäck", nip05 = "jack@example.com"),
            )

        assertEquals(
            listOf(hex),
            RecipientSearch.browse("jack", listOf(discovered), null).map { it.accountIdHex },
        )
        assertEquals(
            listOf(hex),
            RecipientSearch.browse("example.com", listOf(discovered), null).map { it.accountIdHex },
        )
    }

    @Test
    fun mergePreservesKnownChatProvenanceAndRanksFollowedFirst() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val knownAlice =
            candidate(alice, "Alice").copy(
                source = RecipientSearch.Source.InDm,
                existingDmGroupIdHex = "dm-alice",
            )
        val discoveredAlice = candidate(alice.uppercase(), "Alice Remote").copy(searchRadius = 1u)
        val followedBob = candidate(bob, "Bob").copy(searchRadius = 2u, isFollowing = true)

        val merged = RecipientSearch.merge(listOf(knownAlice), listOf(discoveredAlice, followedBob), null)

        assertEquals(listOf(bob, alice), merged.map { it.accountIdHex.lowercase() })
        assertEquals("dm-alice", merged.last().existingDmGroupIdHex)
        assertEquals(1u.toUByte(), merged.last().searchRadius)
    }

    @Test
    fun discoveryOnlyNameMatchKeepsKnownDmProvenance() {
        val hex = "a".repeat(64)
        val known =
            candidate(hex, "Alice").copy(
                source = RecipientSearch.Source.InDm,
                existingDmGroupIdHex = "dm-alice",
            )
        val discovered =
            candidate(hex, "Jack").copy(
                searchProfile = profile(displayName = "Jack"),
            )

        val match =
            RecipientSearch
                .mergeAndBrowse("jack", listOf(known), listOf(discovered), activeAccountIdHex = null)
                .single()

        assertEquals(RecipientSearch.Source.InDm, match.source)
        assertEquals("dm-alice", match.existingDmGroupIdHex)
        assertEquals("Jack", match.searchProfile?.displayName)
    }

    @Test
    fun engineMatchOnAFieldTheLocalFilterCannotSeeIsKept() {
        // The engine matched this person on their `about` text. Nothing in the
        // name/nip05 the client can read contains the needle.
        val hex = "d".repeat(64)
        val discovered =
            candidate(hex, "Zed").copy(
                searchProfile = profile(displayName = "Zed"),
            )

        val matches =
            RecipientSearch.mergeAndBrowse(
                query = "photographer",
                known = emptyList(),
                discovered = listOf(discovered),
                activeAccountIdHex = null,
            )

        assertEquals(listOf(hex), matches.map { it.accountIdHex })
    }

    @Test
    fun engineMatchWithNoPublishedProfileIsKept() {
        val hex = "e".repeat(64)
        // discoveredCandidates falls back to the short npub when there is no
        // kind:0, and a short npub can never contain a name needle.
        val discovered =
            RecipientSearch.discoveredCandidates(
                results = listOf(searchResult(hex, radius = 1u, quality = MatchQualityFfi.EXACT, profile = null)),
                followedAccountIds = emptySet(),
            )

        val matches =
            RecipientSearch.mergeAndBrowse(
                query = "alice",
                known = emptyList(),
                discovered = discovered,
                activeAccountIdHex = null,
            )

        assertEquals(listOf(hex), matches.map { it.accountIdHex })
    }

    @Test
    fun nameMatchesStillOutrankEngineOnlyMatches() {
        val named = "a".repeat(64)
        val engineOnly = "b".repeat(64)
        val matches =
            RecipientSearch.mergeAndBrowse(
                query = "alice",
                known = listOf(candidate(named, "Alice Smith")),
                discovered = listOf(candidate(engineOnly, "Zed").copy(searchProfile = profile("Zed"))),
                activeAccountIdHex = null,
            )

        assertEquals(listOf(named, engineOnly), matches.map { it.accountIdHex })
    }

    @Test
    fun followedLocalContactIsFlaggedAndRanksAboveFollowedRemotes() {
        val localFollowed = "a".repeat(64)
        val remoteFollowed = "b".repeat(64)
        val known =
            listOf(
                candidate("c".repeat(64), "Alan"),
                candidate(localFollowed, "Alice").copy(source = RecipientSearch.Source.InDm),
            )
        val discovered = candidate(remoteFollowed, "Alicia").copy(isFollowing = true)

        val matches =
            RecipientSearch.mergeAndBrowse(
                query = "al",
                known = known,
                discovered = listOf(discovered),
                activeAccountIdHex = null,
                // Upper-case to prove the comparison normalizes both sides.
                followedAccountIds = setOf(localFollowed.uppercase(), remoteFollowed.uppercase()),
            )

        assertEquals(
            listOf(localFollowed, remoteFollowed, "c".repeat(64)),
            matches.map { it.accountIdHex },
        )
        assertTrue(matches.first().isFollowing)
        assertEquals(RecipientSearch.Source.InDm, matches.first().source)
    }

    @Test
    fun discoveryFollowFlagIgnoresCasingOfTheFollowList() {
        val bob = "b".repeat(64)
        val candidates =
            RecipientSearch.discoveredCandidates(
                results = listOf(searchResult(bob, radius = 1u, quality = MatchQualityFfi.EXACT)),
                followedAccountIds = setOf(bob.uppercase()),
            )

        assertTrue(candidates.single().isFollowing)
    }

    @Test
    fun discoveryResultsDeduplicateBestRadiusAndPrioritizeFollows() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val candidates =
            RecipientSearch.discoveredCandidates(
                results =
                    listOf(
                        searchResult(alice, radius = 2u, quality = MatchQualityFfi.PREFIX),
                        searchResult(alice.uppercase(), radius = 1u, quality = MatchQualityFfi.EXACT),
                        searchResult(bob, radius = 2u, quality = MatchQualityFfi.CONTAINS),
                    ),
                followedAccountIds = setOf(bob),
            )

        assertEquals(listOf(bob, alice), candidates.map { it.accountIdHex })
        assertEquals(1u.toUByte(), candidates.last().searchRadius)
        assertTrue(candidates.first().isFollowing)
    }

    private fun searchResult(
        hex: String,
        radius: UByte,
        quality: MatchQualityFfi,
        profile: UserProfileMetadataFfi? = profile(displayName = "Person"),
    ) = UserDirectorySearchResultFfi(
        accountIdHex = hex,
        npub = "npub1${hex.lowercase()}",
        radius = radius,
        matchedField = MatchedFieldFfi.DISPLAY_NAME,
        matchQuality = quality,
        providerRank = null,
        profile = profile,
    )

    private fun profile(
        displayName: String,
        nip05: String? = null,
    ) = UserProfileMetadataFfi(
        name = null,
        displayName = displayName,
        about = null,
        picture = null,
        banner = null,
        nip05 = nip05,
        lud16 = null,
    )
}
