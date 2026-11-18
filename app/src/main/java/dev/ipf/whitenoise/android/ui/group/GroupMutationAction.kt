package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.refreshManagementState

/** The group change the details screen is running, which names the row that shows progress. */
internal enum class GroupMutationAction {
    SaveProfile,
    InviteMember,
    RemoveMember,
    PromoteAdmin,
    DemoteAdmin,
    SelfDemoteAdmin,
    TransferAdmin,
    DisappearingMessages,
    Archive,
    Delete,
    Reset,
    Leave,
    CancelLeave,
    EnableDisbanding,
    EnableAccountDevices,
    Disband,
}

internal data class ActiveGroupMutation(
    val action: GroupMutationAction,
    val target: String? = null,
)

/** Visible details follow settled membership as well as metadata, without polling equal native records. */
@Suppress("FunctionNaming") // Compose effect follows the same naming convention as other composables.
@Composable
internal fun GroupManagementSnapshotRefreshEffect(controller: ConversationController) {
    LaunchedEffect(
        controller,
        controller.group,
        controller.members,
        controller.memberRosterState,
    ) {
        controller.refreshManagementState()
    }
}
