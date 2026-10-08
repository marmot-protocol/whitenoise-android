package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.Marmot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Local deletion is only offered for terminal history; settle an actual leave before UI handoff. */
internal suspend fun prepareMaestroDepartedGroup(
    native: Marmot,
    owner: AccountSummaryFfi,
    peer: AccountSummaryFfi,
    group: String,
) {
    withTimeout(30_000L) {
        native.promoteAdmin(owner.label, group, peer.accountIdHex)
        awaitMaestroGroupCondition { native.groupManagementState(peer.label, group).isSelfAdmin }
        native.selfDemoteAdmin(owner.label, group)
        awaitMaestroGroupCondition { native.groupManagementState(owner.label, group).canLeave }
        native.leaveGroup(owner.label, group)
        awaitMaestroGroupCondition {
            native.presentedChatListRow(owner.label, group)?.actions?.canDeleteLocal == true
        }
        val remote = checkNotNull(native.presentedChatListRow(peer.label, group))
        check(!remote.actions.canDeleteLocal && remote.actions.canStartLeave) {
            "Peer must retain active membership after fixture owner departs"
        }
    }
}

/** The caller's existing fixture deadline bounds every native convergence wait. */
private suspend fun awaitMaestroGroupCondition(matches: suspend () -> Boolean) {
    while (!matches()) delay(100L)
}
