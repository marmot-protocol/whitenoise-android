package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.GroupProjector

/**
 * Whether the engine's authoritative self-membership says the local account is
 * no longer in the group: [SelfMembershipFfi.REMOVED] (evicted) or
 * [SelfMembershipFfi.LEFT] (voluntary departure). Both are terminal non-member
 * states; [SelfMembershipFfi.MEMBER] is the only membership-preserving value.
 */
internal fun SelfMembershipFfi.isNonMember(): Boolean = this == SelfMembershipFfi.REMOVED || this == SelfMembershipFfi.LEFT

internal data class ConversationMembershipSeed(
    val members: List<AppGroupMemberRecordFfi>,
    val membersLoaded: Boolean,
    val seededSelfMember: Boolean,
    val seededMembershipKnown: Boolean,
    val membersVerified: Boolean,
)

internal fun conversationMembershipSeed(
    initialGroup: AppGroupRecordFfi,
    initialMemberSnapshot: GroupMemberSnapshot?,
    activeAccountIdHex: String?,
): ConversationMembershipSeed {
    val initialMembers = initialMemberSnapshot?.members.orEmpty()
    val projectedNonMember = initialGroup.selfMembership.isNonMember()
    val projectedMember = initialGroup.selfMembership == SelfMembershipFfi.MEMBER
    val seededMembers =
        if (projectedNonMember) {
            GroupProjector.membersWithoutActiveAccount(initialMembers, activeAccountIdHex)
        } else {
            initialMembers
        }
    val seededSelfMember =
        projectedMember ||
            (
                !projectedNonMember &&
                    initialMembers.any { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
            )
    return ConversationMembershipSeed(
        members = seededMembers,
        membersLoaded = initialMemberSnapshot?.members?.isNotEmpty() == true,
        seededSelfMember = seededSelfMember,
        seededMembershipKnown = projectedMember || projectedNonMember || initialMemberSnapshot != null,
        membersVerified = projectedNonMember,
    )
}

internal class ConversationSelfLeftState(
    seededMembershipKnown: Boolean,
    seededSelfMember: Boolean,
) {
    var selfLeft by mutableStateOf(seededMembershipKnown && !seededSelfMember)
        private set

    fun recordSelfLeft() {
        selfLeft = true
    }

    fun clearSelfLeft() {
        selfLeft = false
    }

    fun isSelfMember(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): Boolean = GroupProjector.isSelfStillMember(members, activeAccountIdHex, selfLeft)

    fun rosterHonoringSelfLeft(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): List<AppGroupMemberRecordFfi> = GroupProjector.rosterHonoringSelfLeft(members, activeAccountIdHex, selfLeft)
}
