package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.StartProfileChatNoActiveAccountException
import dev.ipf.whitenoise.android.state.groupCreateFailureDetail
import dev.ipf.whitenoise.android.state.startProfileChatFailureCopyable
import dev.ipf.whitenoise.android.state.startProfileChatFailureDetail
import dev.ipf.whitenoise.android.state.startProfileChatFailureIsMissingSetup
import dev.ipf.whitenoise.android.state.startProfileChatInviteDetail
import dev.ipf.whitenoise.android.ui.chats.newchat.RecipientPreviewState
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatAttemptResult
import dev.ipf.whitenoise.android.ui.chats.newchat.attemptStartProfileChat
import dev.ipf.whitenoise.android.ui.chats.newchat.canInviteFromEmptyGroup
import dev.ipf.whitenoise.android.ui.chats.newchat.canStartNewGroupCreateAttempt
import dev.ipf.whitenoise.android.ui.chats.newchat.canSubmitNewChatSheet
import dev.ipf.whitenoise.android.ui.chats.newchat.groupContainsResolvedMember
import dev.ipf.whitenoise.android.ui.chats.newchat.newChatMemberRefs
import dev.ipf.whitenoise.android.ui.chats.newchat.newGroupSetupUiState
import dev.ipf.whitenoise.android.ui.chats.newchat.recipientNip05Verified
import dev.ipf.whitenoise.android.ui.chats.newchat.recipientPreviewAllowsSubmit
import dev.ipf.whitenoise.android.ui.chats.newchat.recipientPreviewState
import dev.ipf.whitenoise.android.ui.chats.newchat.resolvedRecipientRefs
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetAvatarAccount
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetMembersPreview
import dev.ipf.whitenoise.android.ui.conversation.shouldShowConversationMembersSubtitle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatFlowTest {
    @Test
    fun newGroupSetupAfterCanonicalCreateFailureShowsRetryOpenSurface() {
        val retryState =
            newGroupSetupUiState(
                retryGroupIdHex = "created-group",
                canCreate = false,
                busy = false,
            )

        assertEquals(R.string.retry, retryState.fabLabelResId)
        assertEquals(R.string.error_chat_created_not_loaded, retryState.statusResId)
        assertFalse(retryState.detailsEditable)
        assertTrue(retryState.submitEnabled)

        val createState =
            newGroupSetupUiState(
                retryGroupIdHex = null,
                canCreate = true,
                busy = false,
            )
        assertEquals(R.string.create, createState.fabLabelResId)
        assertEquals(null, createState.statusResId)
        assertTrue(createState.detailsEditable)
    }

    @Test
    fun canonicalGroupRetryCanStartWithoutCreateFormEligibility() {
        assertTrue(
            canStartNewGroupCreateAttempt(
                busy = false,
                canCreate = false,
                retryGroupIdHex = "created-group",
            ),
        )
        assertFalse(
            canStartNewGroupCreateAttempt(
                busy = true,
                canCreate = false,
                retryGroupIdHex = "created-group",
            ),
        )
    }

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
    fun startProfileChatFailureMapsMissingSetupToHumanCopy() {
        val missing = MarmotKitException.MissingKeyPackage("deadbeef")

        assertEquals(
            AppText.Resource(
                R.string.error_missing_key_package_for,
                listOf("Alice"),
            ),
            startProfileChatFailureDetail(missing) { "Alice" },
        )
        assertFalse(startProfileChatFailureCopyable(missing))

        val missingBlankAccount = MarmotKitException.MissingKeyPackage("  ")
        assertEquals(
            AppText.Resource(R.string.error_missing_key_package),
            startProfileChatFailureDetail(missingBlankAccount) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(missingBlankAccount))
    }

    @Test
    fun startProfileChatDistinguishesInvalidRecipientFromUnusableKeyPackage() {
        val invalidIdentity = MarmotKitException.InvalidIdentity("bad npub")

        assertFalse(startProfileChatFailureIsMissingSetup(invalidIdentity))
        assertEquals(
            AppText.Resource(R.string.error_invalid_identity_reference),
            startProfileChatFailureDetail(invalidIdentity) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(invalidIdentity))
        assertEquals(
            AppText.Resource(R.string.error_invalid_identity_reference),
            groupCreateFailureDetail(invalidIdentity) { "ignored" },
        )

        val invalidKeyPackage = MarmotKitException.InvalidKeyPackageEvent("unsupported cipher suite")
        assertTrue(startProfileChatFailureIsMissingSetup(invalidKeyPackage))
        assertEquals(
            AppText.Resource(R.string.error_missing_key_package),
            startProfileChatFailureDetail(invalidKeyPackage) { "ignored" },
        )
        assertEquals(
            AppText.Resource(R.string.error_missing_key_package),
            groupCreateFailureDetail(invalidKeyPackage) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(invalidKeyPackage))
    }

    @Test
    fun aHydrationPendingGroupReadsAsStillLoadingNotAsACreateFailure() {
        val pending = MarmotKitException.GroupHydrationPending("7c3bdc38")

        assertFalse(startProfileChatFailureIsMissingSetup(pending))
        assertEquals(
            AppText.Resource(R.string.toast_chat_still_loading),
            groupCreateFailureDetail(pending) { "ignored" },
        )
        assertEquals(
            AppText.Resource(R.string.toast_chat_still_loading),
            startProfileChatFailureDetail(pending) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(pending))
    }

    @Test
    fun startProfileChatInviteCopyUsesKnownNameOrGenericFallback() {
        assertEquals(
            AppText.Resource(R.string.invite_to_white_noise_description, listOf("Alice")),
            startProfileChatInviteDetail("Alice"),
        )
        assertEquals(
            AppText.Resource(R.string.unknown_invite_to_white_noise_description),
            startProfileChatInviteDetail("  "),
        )
        assertTrue(startProfileChatFailureIsMissingSetup(MarmotKitException.MissingKeyPackage("deadbeef")))
    }

    @Test
    fun startProfileChatFailureDistinguishesTechnicalFailures() {
        val publishFailure = MarmotKitException.Publish("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(publishFailure) { "ignored" },
        )
        assertTrue(startProfileChatFailureCopyable(publishFailure))
        val runtimeFailure = MarmotKitException.Runtime("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(runtimeFailure) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(runtimeFailure))
        val unexpectedFailure = RuntimeException("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(unexpectedFailure) { "ignored" },
        )
        assertTrue(startProfileChatFailureCopyable(unexpectedFailure))
    }

    @Test
    fun startProfileChatFailureMapsNoActiveAccountToLocalizedCopy() {
        val noActiveAccount = StartProfileChatNoActiveAccountException()

        assertEquals(
            AppText.Resource(R.string.toast_no_active_account),
            startProfileChatFailureDetail(noActiveAccount) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(noActiveAccount))
    }

    @Test
    fun sharedStartChatAttemptCreatesAndOpensFromAuthoritativeRead() =
        runTest {
            val expected = chatListItem(group("Support"), otherMemberAccount = "support", members = emptyList())
            var createdFor: String? = null

            val result =
                attemptStartProfileChat(
                    npub = "npub1support",
                    progressHex = "support",
                    recipientName = "White Noise support",
                    createGroup = {
                        createdFor = it
                        "created-group"
                    },
                    loadCreatedChatListItem = {
                        assertEquals("created-group", it)
                        expected
                    },
                    displayName = { it },
                )

            assertEquals("npub1support", createdFor)
            assertEquals(StartChatAttemptResult.Open(expected), result)
        }

    @Test
    fun sharedStartChatAttemptRetriesCreatedGroupWithoutCreatingDuplicate() =
        runTest {
            var createCalled = false
            val expected = chatListItem(group("Support"), otherMemberAccount = "support", members = emptyList())

            val result =
                attemptStartProfileChat(
                    npub = "npub1support",
                    progressHex = "support",
                    recipientName = "White Noise support",
                    retryGroupIdHex = "created-group",
                    createGroup = {
                        createCalled = true
                        "duplicate-group"
                    },
                    loadCreatedChatListItem = {
                        assertEquals("created-group", it)
                        expected
                    },
                    displayName = { it },
                )

            assertFalse(createCalled)
            assertEquals(StartChatAttemptResult.Open(expected), result)
        }

    @Test
    fun sharedStartChatAttemptOffersRetryByGroupIdAfterAuthoritativeReadFails() =
        runTest {
            val result =
                attemptStartProfileChat(
                    npub = "npub1support",
                    progressHex = "support",
                    recipientName = "White Noise support",
                    retryGroupIdHex = "created-group",
                    createGroup = { error("must not create again") },
                    loadCreatedChatListItem = { throw MarmotKitException.Runtime("sqlite busy") },
                    displayName = { it },
                )

            val failure = result as StartChatAttemptResult.Failed
            assertEquals("created-group", failure.error.retryGroupIdHex)
        }

    @Test
    fun sharedStartChatAttemptMapsMissingKeyPackageToInvitation() =
        runTest {
            val result =
                attemptStartProfileChat(
                    npub = "npub1support",
                    progressHex = "support",
                    recipientName = "White Noise support",
                    createGroup = { throw MarmotKitException.MissingKeyPackage("support") },
                    loadCreatedChatListItem = { error("must not load a failed create") },
                    displayName = { "White Noise support" },
                )

            val failure = result as StartChatAttemptResult.Failed
            assertTrue(failure.error.invitation)
            assertEquals(AppText.Resource(R.string.invite_to_white_noise), failure.error.title)
            assertEquals(
                AppText.Resource(
                    R.string.invite_to_white_noise_description,
                    listOf("White Noise support"),
                ),
                failure.error.detail,
            )
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
    fun conversationMembersSubtitleWaitsForLoadedRoster() {
        assertFalse(
            shouldShowConversationMembersSubtitle(
                membersLoaded = false,
                openedAsDmHint = false,
                groupName = "Marmot Lab",
                memberCount = 0,
            ),
        )
        assertTrue(
            shouldShowConversationMembersSubtitle(
                membersLoaded = true,
                openedAsDmHint = false,
                groupName = "Marmot Lab",
                memberCount = 1,
            ),
        )
    }

    @Test
    fun conversationMembersSubtitleSuppressesJustCreatedDmTransientRoster() {
        assertFalse(
            shouldShowConversationMembersSubtitle(
                membersLoaded = true,
                openedAsDmHint = true,
                groupName = "",
                memberCount = 0,
            ),
        )
        assertFalse(
            shouldShowConversationMembersSubtitle(
                membersLoaded = true,
                openedAsDmHint = true,
                groupName = "",
                memberCount = 1,
            ),
        )
        // Once the roster reaches two nameless members, normal live-DM detection
        // suppresses the subtitle; the 0/1-member cases above are the transient
        // states covered by the open-time hint.
        assertFalse(
            shouldShowConversationMembersSubtitle(
                membersLoaded = true,
                openedAsDmHint = true,
                groupName = "",
                memberCount = 2,
            ),
        )
    }

    @Test
    fun conversationMembersSubtitleTreatsDefaultIgnorableNameAsUnnamedDuringDmHint() {
        // The transient just-created-DM branch must use the same sanitized
        // "unnamed" rule as the eventual two-member DM projection. Raw
        // isBlank() is false for these invisible non-whitespace characters.
        assertFalse(
            shouldShowConversationMembersSubtitle(
                membersLoaded = true,
                openedAsDmHint = true,
                groupName = "\u200B\u200E\uFEFF",
                memberCount = 1,
            ),
        )
    }

    @Test
    fun forwardTargetPresentationTreatsDefaultIgnorableNameAsDirectChat() {
        val item =
            chatListItem(
                group = group(name = "\u200B\u200E\uFEFF"),
                otherMemberAccount = "bob",
                members = listOf(member("alice"), member("bob")),
            )

        assertEquals("bob", forwardTargetAvatarAccount(item))
        assertEquals(
            null,
            forwardTargetMembersPreview(item, activeAccountIdHex = "alice") { id -> titleFor(id) },
        )
    }

    @Test
    fun conversationMembersSubtitleLetsLiveGroupStateOverrideDmHint() {
        assertTrue(
            shouldShowConversationMembersSubtitle(
                membersLoaded = true,
                openedAsDmHint = true,
                groupName = "Marmot Lab",
                memberCount = 1,
            ),
        )
        assertTrue(
            shouldShowConversationMembersSubtitle(
                membersLoaded = true,
                openedAsDmHint = true,
                groupName = "",
                memberCount = 3,
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

    private fun chatListItem(
        group: AppGroupRecordFfi,
        otherMemberAccount: String?,
        members: List<AppGroupMemberRecordFfi>,
    ) = ChatListItem(
        group = group,
        latest = null,
        otherMemberAccount = otherMemberAccount,
        memberCount = members.size,
        memberSnapshot = GroupMemberSnapshot(members),
    )

    private fun group(name: String) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = "group",
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = name,
            description = "A group",
            admins = emptyList(),
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "nostr",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )

    private fun member(id: String) =
        AppGroupMemberRecordFfi(
            memberIdHex = id,
            account = id,
            local = id == "alice",
        )

    private fun titleFor(id: String): String = id.replaceFirstChar { it.titlecase() }
}
