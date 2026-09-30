package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.GroupProjector

internal enum class GroupRosterLoadState {
    LOADING,
    READY,
    FAILED,
    INCONSISTENT,
}

internal enum class GroupRosterRefreshEvent {
    STARTED,
    SUCCEEDED,
    FAILED,
    INCONSISTENT,
}

internal fun reduceGroupRosterLoadState(
    current: GroupRosterLoadState,
    event: GroupRosterRefreshEvent,
): GroupRosterLoadState =
    when (event) {
        GroupRosterRefreshEvent.STARTED ->
            if (current == GroupRosterLoadState.READY) {
                current
            } else {
                GroupRosterLoadState.LOADING
            }
        GroupRosterRefreshEvent.SUCCEEDED -> GroupRosterLoadState.READY
        GroupRosterRefreshEvent.FAILED ->
            if (current == GroupRosterLoadState.READY) {
                current
            } else {
                GroupRosterLoadState.FAILED
            }
        GroupRosterRefreshEvent.INCONSISTENT -> GroupRosterLoadState.INCONSISTENT
    }

internal fun restoreGroupRosterLoadStateAfterCancellation(
    previous: GroupRosterLoadState,
    current: GroupRosterLoadState,
): GroupRosterLoadState =
    if (current != GroupRosterLoadState.LOADING) {
        current
    } else {
        previous.takeUnless { it == GroupRosterLoadState.LOADING } ?: GroupRosterLoadState.FAILED
    }

internal class GroupRosterLoadTracker(
    initial: GroupRosterLoadState,
) {
    var state by mutableStateOf(initial)
        private set

    private var lastSettledState =
        initial.takeUnless { it == GroupRosterLoadState.LOADING }
            ?: GroupRosterLoadState.FAILED

    fun transition(event: GroupRosterRefreshEvent) {
        state = reduceGroupRosterLoadState(state, event)
        if (state != GroupRosterLoadState.LOADING) {
            lastSettledState = state
        }
    }

    fun restoreAfterCancellation() {
        state =
            restoreGroupRosterLoadStateAfterCancellation(
                previous = lastSettledState,
                current = state,
            )
    }
}

internal data class AppliedGroupDetails(
    val group: AppGroupRecordFfi,
    val members: List<AppGroupMemberRecordFfi>,
)

internal enum class GroupRosterInvariant {
    GROUP_ID_MISMATCH,
    EMPTY_JOINED_ROSTER,
    LOCAL_MEMBER_MISSING,
    MEMBER_COUNT_MISMATCH,
}

internal data class GroupRosterResolution(
    val applied: AppliedGroupDetails,
    val invariant: GroupRosterInvariant?,
    val uniqueMemberCount: Int,
    val mlsMemberCount: UInt,
    val containsLocalMember: Boolean,
)

internal fun applyAuthoritativeGroupDetails(details: GroupDetailsFfi): AppliedGroupDetails =
    AppliedGroupDetails(
        group = details.group,
        members =
            GroupProjector.identityDistinctMembers(
                details.members.map { member ->
                    AppGroupMemberRecordFfi(
                        memberIdHex = member.memberIdHex,
                        account = member.account,
                        local = member.local,
                    )
                },
            ),
    )

internal fun resolveAuthoritativeGroupRoster(
    details: GroupDetailsFfi,
    activeAccountIdHex: String?,
): GroupRosterResolution {
    val applied = applyAuthoritativeGroupDetails(details)
    val uniqueMemberCount = GroupProjector.uniqueMemberCount(applied.members)
    val containsLocalMember =
        details.members.any { member ->
            member.isSelf ||
                activeAccountIdHex?.let { accountId ->
                    member.memberIdHex.equals(accountId, ignoreCase = true)
                } == true
        }
    val activeJoinedGroup =
        details.group.selfMembership == SelfMembershipFfi.MEMBER &&
            !details.group.pendingConfirmation
    val invariant =
        when {
            !activeJoinedGroup -> null
            uniqueMemberCount == 0 -> GroupRosterInvariant.EMPTY_JOINED_ROSTER
            !containsLocalMember -> GroupRosterInvariant.LOCAL_MEMBER_MISSING
            details.members.size.toLong() !=
                details.mlsState.memberCount.toLong() -> GroupRosterInvariant.MEMBER_COUNT_MISMATCH
            else -> null
        }
    return GroupRosterResolution(
        applied = applied,
        invariant = invariant,
        uniqueMemberCount = uniqueMemberCount,
        mlsMemberCount = details.mlsState.memberCount,
        containsLocalMember = containsLocalMember,
    )
}

/** Convert the lightweight MDK roster projection without a second details read. */
internal fun applyAuthoritativeGroupRoster(
    currentGroup: AppGroupRecordFfi,
    roster: GroupRosterFfi,
): AppliedGroupDetails =
    AppliedGroupDetails(
        group =
            currentGroup.copy(
                admins = roster.members.filter { it.isAdmin }.map { it.memberIdHex },
                selfMembership = roster.selfMembership,
                unrecoverable = roster.lifecycleState == GroupLifecycleStateFfi.UNRECOVERABLE,
                disbanded = roster.lifecycleState == GroupLifecycleStateFfi.DISBANDED,
            ),
        members =
            GroupProjector.identityDistinctMembers(
                roster.members.map { member ->
                    AppGroupMemberRecordFfi(
                        memberIdHex = member.memberIdHex,
                        account = member.account,
                        local = member.local,
                    )
                },
            ),
    )

internal fun resolveAuthoritativeGroupRoster(
    currentGroup: AppGroupRecordFfi,
    roster: GroupRosterFfi,
    activeAccountIdHex: String?,
): GroupRosterResolution {
    val applied = applyAuthoritativeGroupRoster(currentGroup, roster)
    val uniqueMemberCount = GroupProjector.uniqueMemberCount(applied.members)
    val matchesCurrentGroup =
        currentGroup.groupIdHex.trim().equals(roster.groupIdHex.trim(), ignoreCase = true)
    val containsLocalMember =
        roster.members.any { member ->
            member.isSelf ||
                activeAccountIdHex?.let { accountId ->
                    member.memberIdHex.equals(accountId, ignoreCase = true)
                } == true
        }
    val activeJoinedGroup =
        applied.group.selfMembership == SelfMembershipFfi.MEMBER &&
            !applied.group.pendingConfirmation
    val invariant =
        when {
            !matchesCurrentGroup -> GroupRosterInvariant.GROUP_ID_MISMATCH
            !activeJoinedGroup -> null
            uniqueMemberCount == 0 -> GroupRosterInvariant.EMPTY_JOINED_ROSTER
            !containsLocalMember -> GroupRosterInvariant.LOCAL_MEMBER_MISSING
            uniqueMemberCount.toLong() != roster.memberCount.toLong() ->
                GroupRosterInvariant.MEMBER_COUNT_MISMATCH
            else -> null
        }
    return GroupRosterResolution(
        applied = applied,
        invariant = invariant,
        uniqueMemberCount = uniqueMemberCount,
        mlsMemberCount = roster.memberCount,
        containsLocalMember = containsLocalMember,
    )
}

/** A newer canonical group observation invalidated the captured roster read. */
internal class SupersededGroupRosterRead : Exception()
