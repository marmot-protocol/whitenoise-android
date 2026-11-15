package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupCreationResultFfi
import dev.ipf.marmotkit.GroupInviteResultFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MemberResolutionOutcomeFfi
import dev.ipf.marmotkit.MemberResolutionStatusFfi
import dev.ipf.marmotkit.MissingKeyPackageReasonFfi
import dev.ipf.whitenoise.android.R

/** Preserves typed single-recipient recovery for group creation and invites with no commit. */
internal fun groupMemberSelectionFailure(outcomes: List<MemberResolutionOutcomeFfi>): Throwable {
    val outcome = outcomes.singleOrNull() ?: return NoGroupMembersSelectedException(outcomes)
    return when (outcome.status) {
        MemberResolutionStatusFfi.MISSING_KEY_PACKAGE ->
            MarmotKitException.MissingKeyPackage(outcome.accountIdHex, MissingKeyPackageReasonFfi.NOT_FOUND)
        MemberResolutionStatusFfi.OBSOLETE_KEY_PACKAGE ->
            MarmotKitException.MissingKeyPackage(outcome.accountIdHex, MissingKeyPackageReasonFfi.OBSOLETE_ONLY)
        MemberResolutionStatusFfi.MISSING_INBOX_ROUTE ->
            MarmotKitException.MissingMemberInboxRoute(outcome.accountIdHex)
        MemberResolutionStatusFfi.INCOMPLETE -> MarmotKitException.DirectoryLookupIncomplete(outcome.accountIdHex)
        MemberResolutionStatusFfi.INVALID_KEY_PACKAGE -> MarmotKitException.InvalidKeyPackageEvent("Invalid KeyPackage")
        MemberResolutionStatusFfi.INCOMPATIBLE_KEY_PACKAGE,
        MemberResolutionStatusFfi.SELECTED,
        -> NoGroupMembersSelectedException(outcomes)
    }
}

/** An absent group ID means the submitted nonempty selection produced no commit. */
internal fun GroupCreationResultFfi.requireCreatedGroupId(): String =
    groupIdHex
        ?: throw groupMemberSelectionFailure(memberOutcomes)

/** An absent invite summary means the selection produced no membership commit. */
internal fun GroupInviteResultFfi.requireAcceptedInvite(): GroupInviteResultFfi {
    if (invite.summary == null) throw groupMemberSelectionFailure(invite.memberOutcomes)
    return this
}

/** Resolves only skipped recipients and leaves unnamed recipients out of the display list. */
internal fun List<MemberResolutionOutcomeFfi>.skippedGroupMemberNames(displayName: (String) -> String): List<String> =
    skippedGroupMemberRefs()
        .map(displayName)
        .filter(String::isNotBlank)

/** Preserves a partial result even when some skipped recipients have no display name. */
internal fun List<MemberResolutionOutcomeFfi>.skippedGroupMembersDetail(displayName: (String) -> String): AppText? =
    skippedGroupMemberRefs().missingGroupMembersDetail(displayName)

/** A missing name keeps the total visible, without implying that the named subset was the entire result. */
internal fun List<String>.missingGroupMembersDetail(displayName: (String) -> String): AppText? {
    if (isEmpty()) return null
    val names = map(displayName)
    return if (names.any(String::isBlank)) {
        AppText.Resource(R.string.group_members_not_added_count, listOf(size))
    } else {
        AppText.Plain(names.joinToString())
    }
}

/** Reads the shared runtime's eligibility result independently of its display names. */
private fun List<MemberResolutionOutcomeFfi>.skippedGroupMemberRefs(): List<String> =
    filter { it.status != MemberResolutionStatusFfi.SELECTED }.map(MemberResolutionOutcomeFfi::accountIdHex)
