package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatFlowTest {
    @Test
    fun groupCreateRequiresNameButNoRecipients() {
        assertTrue(
            canSubmitNewChatSheet(
                directMessage = false,
                busy = false,
                pendingRecipient = "",
                groupName = "Friends",
            ),
        )
        assertFalse(
            canSubmitNewChatSheet(
                directMessage = false,
                busy = false,
                pendingRecipient = "npub1alice",
                groupName = "",
            ),
        )
    }

    @Test
    fun groupCreateStartsWithNoInvitedMembers() {
        assertEquals(
            emptyList<String>(),
            newChatMemberRefs(
                directMessage = false,
                normalizedPendingRecipients = listOf("npub1alice"),
            ),
        )
    }

    @Test
    fun groupCreateKeepsInitialInvitedMembersOnly() {
        assertEquals(
            listOf("alice", "bob"),
            newChatMemberRefs(
                directMessage = false,
                normalizedPendingRecipients = listOf("ignored"),
                initialMemberRefs = listOf(" alice ", "bob", "ALICE", ""),
            ),
        )
    }

    @Test
    fun directMessageStillRequiresAndKeepsOneRecipient() {
        assertFalse(
            canSubmitNewChatSheet(
                directMessage = true,
                busy = false,
                pendingRecipient = "",
                groupName = "",
            ),
        )
        assertTrue(
            canSubmitNewChatSheet(
                directMessage = true,
                busy = false,
                pendingRecipient = "npub1alice",
                groupName = "",
            ),
        )
        assertEquals(
            listOf("npub1alice"),
            newChatMemberRefs(
                directMessage = true,
                normalizedPendingRecipients = listOf("npub1alice", "npub1bob", "npub1alice"),
            ),
        )
    }

    @Test
    fun recipientPreviewMapsResolutionSignalsToState() {
        // Empty input -> no card.
        assertEquals(
            RecipientPreviewState.Empty,
            recipientPreviewState(hasInput = false, resolving = false, resolvedHex = null, hasProfile = false),
        )
        // Resolving wins over a not-yet-known key (NIP-05 lookup / kind:0 fetch).
        assertEquals(
            RecipientPreviewState.Resolving,
            recipientPreviewState(hasInput = true, resolving = true, resolvedHex = null, hasProfile = false),
        )
        // Settled with no key -> invalid.
        assertEquals(
            RecipientPreviewState.Invalid,
            recipientPreviewState(hasInput = true, resolving = false, resolvedHex = null, hasProfile = false),
        )
        // Resolved with metadata -> full card.
        assertEquals(
            RecipientPreviewState.Loaded,
            recipientPreviewState(hasInput = true, resolving = false, resolvedHex = "deadbeef", hasProfile = true),
        )
        // Resolved but no metadata -> fallback card.
        assertEquals(
            RecipientPreviewState.NoProfile,
            recipientPreviewState(hasInput = true, resolving = false, resolvedHex = "deadbeef", hasProfile = false),
        )
    }

    @Test
    fun recipientPreviewGatesSubmitOnlyForResolvingOrInvalid() {
        // Loaded and no-profile both confirm a real key -> action allowed.
        assertTrue(recipientPreviewAllowsSubmit(RecipientPreviewState.Loaded))
        assertTrue(recipientPreviewAllowsSubmit(RecipientPreviewState.NoProfile))
        // Empty defers to the surface's own validation (e.g. group create with
        // no recipient field) -> not blocked here.
        assertTrue(recipientPreviewAllowsSubmit(RecipientPreviewState.Empty))
        // In-flight / unresolvable identifiers block the action.
        assertFalse(recipientPreviewAllowsSubmit(RecipientPreviewState.Resolving))
        assertFalse(recipientPreviewAllowsSubmit(RecipientPreviewState.Invalid))
    }

    @Test
    fun emptyGroupInviteCtaRequiresLoadedAdminSelfOnlyGroup() {
        assertTrue(
            canInviteFromEmptyGroup(
                isSelfMember = true,
                isSelfAdmin = true,
                membersLoaded = true,
                memberCount = 1,
            ),
        )
        assertFalse(
            canInviteFromEmptyGroup(
                isSelfMember = true,
                isSelfAdmin = true,
                membersLoaded = true,
                memberCount = 0,
            ),
        )
        assertFalse(
            canInviteFromEmptyGroup(
                isSelfMember = true,
                isSelfAdmin = true,
                membersLoaded = true,
                memberCount = 2,
            ),
        )
        assertFalse(
            canInviteFromEmptyGroup(
                isSelfMember = true,
                isSelfAdmin = false,
                membersLoaded = true,
                memberCount = 1,
            ),
        )
        assertFalse(
            canInviteFromEmptyGroup(
                isSelfMember = false,
                isSelfAdmin = true,
                membersLoaded = true,
                memberCount = 1,
            ),
        )
        assertFalse(
            canInviteFromEmptyGroup(
                isSelfMember = true,
                isSelfAdmin = true,
                membersLoaded = false,
                memberCount = 1,
            ),
        )
    }

    @Test
    fun resolvedRecipientRefsPrefersResolvedKeyOverFallback() {
        val resolvedKey = "a".repeat(64)
        // A resolved hex (e.g. from a NIP-05 the normalize path can't parse) is
        // what actually gets submitted — this is the #631 blocking fix: the
        // create/invite ships the key the preview confirmed, not the raw input.
        assertEquals(
            listOf(resolvedKey),
            resolvedRecipientRefs(resolvedHex = resolvedKey, normalizedFallback = listOf("npub1bob")),
        )
    }

    @Test
    fun resolvedRecipientRefsFallsBackToNormalizedWhenNoResolvedKey() {
        // Direct npub/hex entry has no separate resolved hex hoisted; the
        // tokenize+normalize fallback drives the submission, unchanged.
        assertEquals(
            listOf("npub1alice", "npub1bob"),
            resolvedRecipientRefs(resolvedHex = null, normalizedFallback = listOf("npub1alice", "npub1bob")),
        )
        // Nothing resolvable -> null so the caller surfaces its validation error.
        assertEquals(
            null,
            resolvedRecipientRefs(resolvedHex = null, normalizedFallback = emptyList()),
        )
    }

    @Test
    fun groupContainsResolvedMemberMatchesRosterCaseInsensitively() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        // The resolved pubkey already holds a seat -> pre-check trips (#899),
        // so Add is disabled and the doomed DuplicateSignatureKey invite never
        // fires. Hex comparison is case-insensitive, like every roster check.
        assertTrue(
            groupContainsResolvedMember(
                memberHexes = listOf(alice, bob),
                resolvedHex = alice.uppercase(),
            ),
        )
        // Resolved to someone not in the group -> addable.
        assertFalse(
            groupContainsResolvedMember(
                memberHexes = listOf(alice),
                resolvedHex = bob,
            ),
        )
        // Nothing resolved yet (null/blank) -> never blocks on this basis.
        assertFalse(groupContainsResolvedMember(memberHexes = listOf(alice), resolvedHex = null))
        assertFalse(groupContainsResolvedMember(memberHexes = listOf(alice), resolvedHex = "   "))
    }

    @Test
    fun recipientNip05VerifiedOnlyWhenItResolvesBackToThePubkey() {
        val pubkey = "b".repeat(64)
        // A declared kind:0 nip05 that resolves back to the same pubkey is the
        // only case that earns a verified check (#631 blocking fix): syntax
        // validity alone is a self-assertion, not verification.
        assertTrue(
            recipientNip05Verified(
                declaredNip05 = "alice@example.com",
                nip05ResolvedHex = pubkey,
                resolvedHex = pubkey,
            ),
        )
        // Matching is case-insensitive on the hex.
        assertTrue(
            recipientNip05Verified(
                declaredNip05 = "alice@example.com",
                nip05ResolvedHex = pubkey.uppercase(),
                resolvedHex = pubkey,
            ),
        )
        // Resolves to a DIFFERENT key (hijack / wrong-clipboard) -> not verified.
        assertFalse(
            recipientNip05Verified(
                declaredNip05 = "alice@example.com",
                nip05ResolvedHex = "c".repeat(64),
                resolvedHex = pubkey,
            ),
        )
        // NIP-05 lookup hasn't completed / failed -> not verified (no false check).
        assertFalse(
            recipientNip05Verified(
                declaredNip05 = "alice@example.com",
                nip05ResolvedHex = null,
                resolvedHex = pubkey,
            ),
        )
        // No nip05 declared at all -> nothing to verify.
        assertFalse(
            recipientNip05Verified(
                declaredNip05 = null,
                nip05ResolvedHex = pubkey,
                resolvedHex = pubkey,
            ),
        )
    }
}
