package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.RecipientSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientDirectoryPresentationTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val discovered =
        RecipientSearch.Candidate(
            bob,
            "Bob",
            "npub1bob",
            searchProfile =
                UserProfileMetadataFfi(
                    name = null,
                    displayName = "Bob",
                    about = null,
                    picture = null,
                    banner = null,
                    nip05 = "bob@example.com",
                    lud16 = null,
                ),
        )

    /** Finishing the domain must not turn a directory match into an empty result. */
    @Test
    fun completingAddressKeepsDirectoryDiscovery() {
        for (query in listOf("bob@example", "bob@example.co", "bob@example.com", " bob@example.com ")) {
            assertTrue(usesRecipientDirectorySearch(query))
            assertEquals(listOf(discovered), matches(query))
        }
    }

    /** Both pending and failed HTTPS resolution have no key; the row stays ordinary discovery. */
    @Test
    fun unresolvedAddressDoesNotPromoteMetadataToResolvedIdentity() {
        assertFalse(isPlainNameQuery("bob@example.com") { null })
        val row = matches("bob@example.com").single()
        assertEquals(discovered, row)
        assertEquals("bob@example.com", row.searchProfile?.nip05)
        assertFalse(row.isFollowing)
    }

    /** A successful lookup wins over duplicate or conflicting public-profile claims. */
    @Test
    fun resolvedAddressSuppressesDirectoryFallbackIncludingSelfAndExcludedMember() {
        for (resolved in listOf(bob, carol, alice)) {
            assertTrue(matches("bob@example.com", resolvedHex = resolved).isEmpty())
        }
    }

    /** Discovery retains known DM provenance, exclusions, follow state and identity deduplication. */
    @Test
    fun fallbackKeepsExistingChatAndExcludesSelfAndMembers() {
        val known = discovered.copy(source = RecipientSearch.Source.InDm, existingDmGroupIdHex = "existing-dm")
        val rows =
            recipientDirectoryMatches(
                "bob@example.com",
                null,
                listOf(known),
                listOf(discovered, discovered.copy(accountIdHex = alice), discovered.copy(accountIdHex = carol)),
                alice,
                excludeAccountIdHexes = setOf(carol.uppercase()),
            )
        assertEquals(1, rows.size)
        assertEquals("existing-dm", rows.single().existingDmGroupIdHex)
        assertEquals(RecipientSearch.Source.InDm, rows.single().source)
    }

    /** Public keys keep their direct-resolution path; no extra native directory traversal is started. */
    @Test
    fun decodedReferencesAndEmptyInputDoNotStartDirectorySearch() {
        for (query in listOf("", "   ", bob, "npub1synthetic")) {
            assertFalse(usesRecipientDirectorySearch(query) { if (it.isNotBlank()) bob else null })
        }
        assertTrue(usesRecipientDirectorySearch("Bob"))
        assertTrue(recipientDirectoryMatches(bob, null, emptyList(), listOf(discovered), alice).isEmpty())
        val npub = "npub1" + "a".repeat(58)
        for (query in listOf(npub, "nostr:$npub", "whitenoise://profile/$npub", "whitenoise-dev://profile/$npub")) {
            assertFalse(usesRecipientDirectorySearch(query))
            assertTrue(matches(query).isEmpty())
        }
        assertTrue(
            recipientDirectoryMatches(
                "nprofile1synthetic",
                null,
                emptyList(),
                listOf(discovered),
                alice,
                accountIdHex = { bob },
            ).isEmpty(),
        )
    }

    private fun matches(
        query: String,
        resolvedHex: String? = null,
    ) = recipientDirectoryMatches(query, resolvedHex, emptyList(), listOf(discovered), alice)
}
