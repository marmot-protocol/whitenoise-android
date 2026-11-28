package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.EventSizeClassFfi
import dev.ipf.marmotkit.GroupCreationResultFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MemberResolutionOutcomeFfi
import dev.ipf.marmotkit.MemberResolutionStatusFfi
import dev.ipf.marmotkit.MemberResolutionStatusFfi.INCOMPATIBLE_KEY_PACKAGE
import dev.ipf.marmotkit.MemberResolutionStatusFfi.INCOMPLETE
import dev.ipf.marmotkit.MemberResolutionStatusFfi.INVALID_KEY_PACKAGE
import dev.ipf.marmotkit.MemberResolutionStatusFfi.MISSING_INBOX_ROUTE
import dev.ipf.marmotkit.MemberResolutionStatusFfi.MISSING_KEY_PACKAGE
import dev.ipf.marmotkit.MemberResolutionStatusFfi.OBSOLETE_KEY_PACKAGE
import dev.ipf.marmotkit.MissingKeyPackageReasonFfi
import dev.ipf.marmotkit.OversizedArtifactFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.NoGroupMembersSelectedException
import dev.ipf.whitenoise.android.state.StartProfileChatNoActiveAccountException
import dev.ipf.whitenoise.android.state.groupCreateFailureCopyable
import dev.ipf.whitenoise.android.state.groupCreateFailureDetail
import dev.ipf.whitenoise.android.state.groupCreateSelectionFailureDetail
import dev.ipf.whitenoise.android.state.groupMemberSelectionFailure
import dev.ipf.whitenoise.android.state.inviteFailureDetail
import dev.ipf.whitenoise.android.state.requireCreatedGroupId
import dev.ipf.whitenoise.android.state.skippedGroupMembersDetail
import dev.ipf.whitenoise.android.state.startProfileChatFailureDetail
import dev.ipf.whitenoise.android.state.startProfileChatFailureIsMissingSetup
import dev.ipf.whitenoise.android.ui.chats.newchat.startChatErrorUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteFailurePresentationTest {
    /** An all-skipped report names everyone or no one, so an unnamed recipient is never left out of a named list. */
    @Test
    fun foundingFailureNamesEveryoneOrNoOne() {
        val report = allSkipped(MISSING_KEY_PACKAGE, MISSING_KEY_PACKAGE)

        assertEquals(
            AppText.Resource(R.string.error_no_group_members_selected),
            groupCreateSelectionFailureDetail(report, null),
        )
        assertEquals(
            AppText.Resource(R.string.error_no_group_members_selected),
            groupCreateSelectionFailureDetail(report, null) { if (it == "alice") "Alice" else " " },
        )
        assertEquals(
            AppText.Resource(R.string.error_no_group_members_selected_for, listOf("Alice, Bob")),
            groupCreateSelectionFailureDetail(report, null, ::named),
        )
    }

    /** A skip reason every recipient shares keeps its own remedy; mixed reasons keep the chat setup guidance. */
    @Test
    fun allSkippedGuidanceFollowsTheSharedReason() {
        val cases =
            listOf(
                allSkipped(INCOMPLETE, INCOMPLETE) to R.string.error_no_group_members_selected_lookup_incomplete_for,
                allSkipped(MISSING_INBOX_ROUTE, MISSING_INBOX_ROUTE) to
                    R.string.error_no_group_members_selected_inbox_for,
                allSkipped(INCOMPATIBLE_KEY_PACKAGE, OBSOLETE_KEY_PACKAGE) to
                    R.string.error_no_group_members_selected_update_for,
                allSkipped(INVALID_KEY_PACKAGE, MISSING_KEY_PACKAGE) to R.string.error_no_group_members_selected_for,
                allSkipped(INCOMPLETE, MISSING_KEY_PACKAGE) to R.string.error_no_group_members_selected_for,
            )

        cases.forEach { (report, resource) ->
            assertEquals(
                AppText.Resource(resource, listOf("Alice, Bob")),
                groupCreateSelectionFailureDetail(report, null, ::named),
            )
            assertEquals(
                AppText.Resource(resource, listOf("Alice, Bob")),
                inviteFailureDetail(report, ::named),
            )
        }
    }

    /** Builds a two-recipient report in which MDK selected no one. */
    private fun allSkipped(
        first: MemberResolutionStatusFfi,
        second: MemberResolutionStatusFfi,
    ) = NoGroupMembersSelectedException(
        listOf(MemberResolutionOutcomeFfi("alice", first, 0u), MemberResolutionOutcomeFfi("bob", second, 0u)),
    )

    /** Resolves the fixture recipients to display names. */
    private fun named(ref: String): String = ref.replaceFirstChar(Char::uppercaseChar)

    /** An incompatible chat key has a reason and action even without a known name. */
    @Test
    fun incompatibleRecipientShowsChatKeyGuidance() {
        val outcome = MemberResolutionOutcomeFfi("alice", MemberResolutionStatusFfi.INCOMPATIBLE_KEY_PACKAGE, 0u)
        val failure = groupMemberSelectionFailure(listOf(outcome))

        assertEquals(
            AppText.Resource(R.string.error_incompatible_key_package_for, listOf("Alice")),
            startProfileChatFailureDetail(failure) { "Alice" },
        )
        assertEquals(
            AppText.Resource(R.string.error_incompatible_key_package),
            startProfileChatFailureDetail(failure) { " " },
        )
        assertFalse(startProfileChatFailureIsMissingSetup(failure))
        assertFalse(groupCreateFailureCopyable(failure))
    }

    /** Blank names cannot make a partial commit appear to have added everyone. */
    @Test
    fun partialCommitCountsAllSkippedPeopleWhenSomeNamesAreBlank() {
        val selected = MemberResolutionOutcomeFfi("alice", MemberResolutionStatusFfi.SELECTED, 1u)
        val unavailable = MemberResolutionStatusFfi.MISSING_KEY_PACKAGE
        val outcomes = listOf(selected, MemberResolutionOutcomeFfi("bob", unavailable, 0u))
        val twoSkipped = outcomes + MemberResolutionOutcomeFfi("carol", unavailable, 0u)

        assertEquals(
            AppText.Resource(R.string.group_members_not_added_count, listOf(1)),
            outcomes.skippedGroupMembersDetail { " " },
        )
        assertEquals(
            AppText.Resource(R.string.group_members_not_added_count, listOf(2)),
            twoSkipped.skippedGroupMembersDetail { if (it == "bob") "Bob" else "" },
        )
        assertNull(listOf(selected).skippedGroupMembersDetail { "" })
    }

    /** An explicit solo create can return a valid group ID without any recipient outcomes. */
    @Test
    fun emptySelectionCanCreateASoloGroup() {
        val result = GroupCreationResultFfi("solo-group", emptyList())

        assertEquals("solo-group", result.requireCreatedGroupId())
        assertNull(result.memberOutcomes.skippedGroupMembersDetail { "" })
    }

    /** Every native single-recipient rejection keeps its typed recovery reason. */
    @Test
    fun singleRecipientOutcomesPreserveTypedFailures() {
        val cases =
            mapOf(
                MemberResolutionStatusFfi.MISSING_KEY_PACKAGE to MarmotKitException.MissingKeyPackage::class.java,
                MemberResolutionStatusFfi.OBSOLETE_KEY_PACKAGE to MarmotKitException.MissingKeyPackage::class.java,
                MemberResolutionStatusFfi.INVALID_KEY_PACKAGE to MarmotKitException.InvalidKeyPackageEvent::class.java,
                MemberResolutionStatusFfi.MISSING_INBOX_ROUTE to MarmotKitException.MissingMemberInboxRoute::class.java,
                MemberResolutionStatusFfi.INCOMPLETE to MarmotKitException.DirectoryLookupIncomplete::class.java,
                MemberResolutionStatusFfi.INCOMPATIBLE_KEY_PACKAGE to NoGroupMembersSelectedException::class.java,
                MemberResolutionStatusFfi.SELECTED to NoGroupMembersSelectedException::class.java,
            )
        cases.forEach { (status, type) ->
            val outcome = MemberResolutionOutcomeFfi("alice", status, 0u)
            val failure = assertThrows(type) { GroupCreationResultFfi(null, listOf(outcome)).requireCreatedGroupId() }
            if (failure is MarmotKitException.MissingKeyPackage) {
                val expected =
                    if (status == MemberResolutionStatusFfi.MISSING_KEY_PACKAGE) {
                        MissingKeyPackageReasonFfi.NOT_FOUND
                    } else {
                        MissingKeyPackageReasonFfi.OBSOLETE_ONLY
                    }
                assertEquals(expected, failure.reason)
                assertEquals("alice", failure.account)
            }
        }
    }

    /** Unmatched typed identities keep the precise reason without blaming a selected person. */
    @Test fun foundingFailureRequiresCapturedRecipientForNamedCopy() {
        val missing = MarmotKitException.MissingKeyPackage("outside-selection", MissingKeyPackageReasonFfi.NOT_FOUND)
        assertEquals(
            AppText.Resource(R.string.error_missing_key_package),
            groupCreateSelectionFailureDetail(missing, null),
        )
        assertEquals(
            AppText.Resource(R.string.error_missing_key_package_for, listOf("Ada")),
            groupCreateSelectionFailureDetail(missing, "Ada"),
        )
        assertEquals(
            AppText.Resource(R.string.error_missing_member_inbox),
            groupCreateSelectionFailureDetail(MarmotKitException.MissingMemberInboxRoute("outside"), null),
        )
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            groupCreateSelectionFailureDetail(MarmotKitException.InvalidKeyPackageEvent("private details"), null),
        )
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            groupCreateSelectionFailureDetail(MarmotKitException.Publish("offline"), null),
        )
    }

    /** A full disk asks the user to free space rather than blaming the recipient or offering a bare retry. */
    @Test
    fun aFullDiskAsksForSpaceInsteadOfTheGenericFailure() {
        val full = MarmotKitException.StorageFull("synthetic native reserve")

        assertEquals(AppText.Resource(R.string.error_storage_full), groupCreateFailureDetail(full) { "Alice" })
        assertEquals(AppText.Resource(R.string.error_storage_full), startProfileChatFailureDetail(full) { "Alice" })
    }

    /** A Welcome too large for every inbox relay says so instead of offering a retry that cannot succeed. */
    @Test
    fun anOversizedWelcomeSaysTheInvitationIsTooLarge() {
        val welcome = MarmotKitException.EventTooLarge(OversizedArtifactFfi.WELCOME, EventSizeClassFfi.FROM64_TO128_KIB)
        val commit = MarmotKitException.EventTooLarge(OversizedArtifactFfi.COMMIT, EventSizeClassFfi.FROM64_TO128_KIB)

        assertEquals(
            AppText.Resource(R.string.error_invite_welcome_too_large),
            groupCreateFailureDetail(welcome) { "Alice" },
        )
        assertEquals(
            AppText.Resource(R.string.error_invite_welcome_too_large),
            startProfileChatFailureDetail(welcome) { "Alice" },
        )
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            groupCreateFailureDetail(commit) { "Alice" },
        )
    }

    /** Obsolete-only KeyPackages ask the recipient to update, never to set up, and offer no install link. */
    @Test
    fun obsoleteOnlyKeyPackagesAskTheRecipientToUpdate() {
        val obsolete = MarmotKitException.MissingKeyPackage("deadbeef", MissingKeyPackageReasonFfi.OBSOLETE_ONLY)

        assertEquals(
            AppText.Resource(R.string.error_obsolete_key_package_for, listOf("Alice")),
            startProfileChatFailureDetail(obsolete) { "Alice" },
        )
        assertEquals(
            AppText.Resource(R.string.error_obsolete_key_package),
            groupCreateSelectionFailureDetail(obsolete, null),
        )
        assertFalse(startProfileChatFailureIsMissingSetup(obsolete))
        assertTrue(
            startProfileChatFailureIsMissingSetup(
                MarmotKitException.MissingKeyPackage("deadbeef", MissingKeyPackageReasonFfi.NOT_FOUND),
            ),
        )
        assertFalse(groupCreateFailureCopyable(obsolete))
    }

    /** An incomplete directory lookup is a retryable problem for that recipient, never proof of missing setup. */
    @Test
    fun incompleteLookupIsRetryableNotMissingSetup() {
        val incomplete = MarmotKitException.DirectoryLookupIncomplete("deadbeef")

        assertEquals(
            AppText.Resource(R.string.error_directory_lookup_incomplete_for, listOf("Alice")),
            startProfileChatFailureDetail(incomplete) { "Alice" },
        )
        assertEquals(
            AppText.Resource(R.string.error_directory_lookup_incomplete),
            startProfileChatFailureDetail(MarmotKitException.DirectoryLookupIncomplete(" ")) { "ignored" },
        )
        assertEquals(
            AppText.Resource(R.string.error_directory_lookup_incomplete),
            groupCreateSelectionFailureDetail(incomplete, null),
        )
        assertFalse(startProfileChatFailureIsMissingSetup(incomplete))
        assertFalse(groupCreateFailureCopyable(incomplete))
    }

    /** A confirmed missing KeyPackage reads as missing setup, not as a native error. */
    @Test
    fun startProfileChatFailureMapsMissingSetupToHumanCopy() {
        val missing = MarmotKitException.MissingKeyPackage("deadbeef", MissingKeyPackageReasonFfi.NOT_FOUND)

        assertEquals(
            AppText.Resource(
                R.string.error_missing_key_package_for,
                listOf("Alice"),
            ),
            startProfileChatFailureDetail(missing) { "Alice" },
        )
        assertFalse(groupCreateFailureCopyable(missing))

        val missingBlankAccount = MarmotKitException.MissingKeyPackage("  ", MissingKeyPackageReasonFfi.NOT_FOUND)
        assertEquals(
            AppText.Resource(R.string.error_missing_key_package),
            startProfileChatFailureDetail(missingBlankAccount) { "ignored" },
        )
        assertFalse(groupCreateFailureCopyable(missingBlankAccount))
    }

    @Test
    fun startProfileChatDistinguishesInvalidRecipientFromUnusableKeyPackage() {
        val invalidIdentity = MarmotKitException.InvalidIdentity("bad npub")

        assertFalse(startProfileChatFailureIsMissingSetup(invalidIdentity))
        assertEquals(
            AppText.Resource(R.string.error_invalid_identity_reference),
            startProfileChatFailureDetail(invalidIdentity) { "ignored" },
        )
        assertFalse(groupCreateFailureCopyable(invalidIdentity))
        assertEquals(
            AppText.Resource(R.string.error_invalid_identity_reference),
            groupCreateFailureDetail(invalidIdentity) { "ignored" },
        )

        val invalidKeyPackage = MarmotKitException.InvalidKeyPackageEvent("unsupported cipher suite")
        assertFalse(startProfileChatFailureIsMissingSetup(invalidKeyPackage))
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            startProfileChatFailureDetail(invalidKeyPackage) { "ignored" },
        )
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            groupCreateFailureDetail(invalidKeyPackage) { "ignored" },
        )
        assertFalse(groupCreateFailureCopyable(invalidKeyPackage))
    }

    @Test
    fun groupFailureNamesTheAffectedInboxRecipientAndNeverExposesNativeDetails() {
        val missing = MarmotKitException.MissingMemberInboxRoute(" bob ")
        assertEquals(
            AppText.Resource(R.string.error_missing_member_inbox_for, listOf("Bob")),
            groupCreateFailureDetail(missing) { account ->
                assertEquals("bob", account)
                "Bob"
            },
        )
        assertEquals(
            AppText.Resource(R.string.error_missing_member_inbox),
            groupCreateFailureDetail(MarmotKitException.MissingMemberInboxRoute("  ")) { error("No account") },
        )
        val invalid = MarmotKitException.InvalidKeyPackageEvent("secret relay URL and identity")
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            groupCreateFailureDetail(invalid) { error("No typed affected account") },
        )
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
        assertFalse(groupCreateFailureCopyable(pending))
    }

    @Test
    fun startChatCreationAndReadFailuresUseTheSameFallback() {
        val failures =
            listOf(
                MarmotKitException.Publish("relay unreachable"),
                MarmotKitException.Runtime("relay unreachable"),
                IllegalStateException("unexpected failure"),
            )
        failures.forEach { failure ->
            val state = startChatErrorUiState("npub", "progress", failure, "Alice") { "Alice" }
            assertEquals(startProfileChatFailureDetail(failure) { "Alice" }, state.detail)
            assertEquals(AppText.Resource(R.string.error_group_create_failed_retry), state.detail)
            assertFalse(state.invitation)
        }
    }

    @Test
    fun startProfileChatFailureDistinguishesTechnicalFailures() {
        val publishFailure = MarmotKitException.Publish("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(publishFailure) { "ignored" },
        )
        assertTrue(groupCreateFailureCopyable(publishFailure))
        val runtimeFailure = MarmotKitException.Runtime("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(runtimeFailure) { "ignored" },
        )
        assertFalse(groupCreateFailureCopyable(runtimeFailure))
        val unexpectedFailure = RuntimeException("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(unexpectedFailure) { "ignored" },
        )
        assertTrue(groupCreateFailureCopyable(unexpectedFailure))
    }

    @Test
    fun startProfileChatFailureMapsNoActiveAccountToLocalizedCopy() {
        val noActiveAccount = StartProfileChatNoActiveAccountException()

        assertEquals(
            AppText.Resource(R.string.toast_no_active_account),
            startProfileChatFailureDetail(noActiveAccount) { "ignored" },
        )
        assertFalse(groupCreateFailureCopyable(noActiveAccount))
    }
}
