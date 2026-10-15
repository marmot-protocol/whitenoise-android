package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupConversationSnapshotFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupManagementStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi

private const val ACCOUNT = "alice"
private val SELF = ConversationTimelineTestIds.ACCOUNT_ID

internal fun groupConversationSnapshotFixture(
    name: String,
    admin: Boolean,
): GroupConversationSnapshotFfi {
    val group = conversationTimelineTestGroup().copy(name = name, admins = if (admin) listOf(SELF) else emptyList())
    return GroupConversationSnapshotFfi(details(group, admin), management(admin))
}

private fun details(
    group: AppGroupRecordFfi,
    admin: Boolean,
) = GroupDetailsFfi(
    group = group,
    members = listOf(GroupMemberDetailsFfi(SELF, ACCOUNT, true, admin, true, "npub-alice", null)),
    mlsState =
        AppGroupMlsStateFfi(
            groupIdHex = group.groupIdHex,
            protocolProfile = group.protocolProfile,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            epoch = 0uL,
            memberCount = 1u,
            unrecoverable = false,
            requiredAppComponents = emptyList(),
            disbandingEnabled = false,
            disbanding = false,
            disbandingBlockers = emptyList(),
            disbandRequest = null,
        ),
)

private fun management(admin: Boolean) =
    GroupManagementStateFfi(
        myAccountIdHex = SELF,
        isSelfAdmin = admin,
        isLastAdmin = admin,
        canInvite = admin,
        canLeave = !admin,
        requiresSelfDemoteBeforeLeave = admin,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbandingEnabled = false,
        disbanding = false,
        canEnableDisbanding = false,
        canDisband = false,
        disbandingBlockers = emptyList(),
        disbandRequest = null,
        memberActions = emptyList(),
    )
