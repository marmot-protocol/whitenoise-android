package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupCreationResultFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MemberResolutionOutcomeFfi
import dev.ipf.marmotkit.MemberResolutionStatusFfi
import dev.ipf.marmotkit.MissingKeyPackageReasonFfi

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

/** Reports the skipped recipients using the existing add-members feedback. */
internal fun List<MemberResolutionOutcomeFfi>.skippedGroupMembersDetail(displayName: (String) -> String): AppText? =
    filter { it.status != MemberResolutionStatusFfi.SELECTED }
        .map { displayName(it.accountIdHex) }
        .filter(String::isNotBlank)
        .takeIf { it.isNotEmpty() }
        ?.joinToString()
        ?.let(AppText::Plain)
