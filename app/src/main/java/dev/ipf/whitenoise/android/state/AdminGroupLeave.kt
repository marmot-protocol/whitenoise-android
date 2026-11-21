package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Let MDK perform the atomic admin leave whenever the current account has that capability. */
internal suspend fun WhiteNoiseAppState.leaveAsAdmin(
    account: String,
    groupId: String,
    demoteAndLeave: suspend () -> Unit,
): Boolean {
    val management = marmotIo { groupManagementState(account, groupId) }
    return when {
        management.canLeave -> {
            marmotIo { leaveGroup(account, groupId) }
            true
        }
        management.requiresSelfDemoteBeforeLeave && !management.isLastAdmin -> {
            demoteAndLeave()
            true
        }
        else -> {
            present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
            false
        }
    }
}

/**
 * Steps [account] down as admin, then leaves, asking first if MDK holds the step-down back. The question stays
 * cancellable; once the step-down starts, it, [onDemoted] and the leave run to completion together.
 */
internal suspend fun ConversationController.demoteThenLeave(
    account: String,
    onDemoted: () -> Unit,
) {
    appState.runGroupChange(account, group.groupIdHex, actionLabel = R.string.group_action_step_down_and_leave) {
        withContext(NonCancellable) {
            val demoteResult =
                appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                    selfDemoteAdminDetailed(account, group.groupIdHex)
                }
            onDemoted()
            applyMutationDetails(account, demoteResult.details)
            appState.marmotIo { leaveGroup(account, group.groupIdHex) }
        }
    }
}
