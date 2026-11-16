package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupCommitRefusedRelayFfi
import dev.ipf.whitenoise.android.R

/**
 * Reads which relays refused the user's confirmed changes to this chat, or null when the read failed, which says
 * nothing about the refusal. It runs beside the parked-flag read, so it refreshes on entry, on each group-state edge,
 * on foreground return and on Retry. Relays that are only owed a change are not refusals and show nothing.
 */
internal suspend fun ConversationController.readGroupCommitRefusal(
    account: String,
    groupIdHex: String,
): List<GroupCommitRefusedRelayFfi>? {
    val reader = groupCommitRefusal.deliveryReader
    val delivery =
        runCatchingCancellable {
            if (reader != null) {
                reader(account, groupIdHex)
            } else {
                appState.marmotIo { groupCommitDelivery(account, groupIdHex) }
            }
        }
    // A null delivery means nothing is owed and every refusal was acknowledged.
    return if (delivery.isSuccess) delivery.getOrNull()?.refusedRelays.orEmpty() else null
}

/** Publishes [refused] as this chat's refusal banner, and an empty list also clears an earlier dismissal failure. */
internal fun ConversationController.publishGroupCommitRefusal(refused: List<GroupCommitRefusedRelayFfi>) {
    groupCommitRefusal.refusedRelays = refused
    if (refused.isEmpty()) groupCommitRefusal.dismissFailure = null
}

/**
 * Dismisses this chat's relay refusals for good through MDK, which then allows group changes again, and rereads the
 * chat's state. As on iOS, the banner clears through that reread, so a reread that fails keeps it on screen. A
 * failure shows a localized reason only. No group commit lock is taken: nothing is committed, and the lock is held for
 * whole uploads. Acknowledging also admits one change attempt that relays still owe, which MDK keeps in memory.
 */
internal suspend fun ConversationController.dismissGroupCommitRefusal() {
    val state = groupCommitRefusal
    val account = conversationAccountRef
    if (account == null || state.dismissing || state.refusedRelays.isEmpty()) return
    val groupIdHex = group.groupIdHex
    val runtimeGeneration = appState.runtimeGeneration
    val owns = { ownsGroupRecoveryRead(account, groupIdHex, runtimeGeneration) }
    state.dismissing = true
    state.dismissFailure = null
    try {
        val acknowledger = state.acknowledger
        runCatchingCancellable {
            if (acknowledger != null) {
                acknowledger(account, groupIdHex)
            } else {
                appState.marmotIo { acknowledgeGroupCommitUndelivered(account, groupIdHex) }
            }
        }.onSuccess {
            readGroupConvergenceParkedAfterRetry()
        }.onFailure {
            if (owns()) state.dismissFailure = R.string.group_commit_refusal_dismiss_failed
        }
    } finally {
        state.dismissing = false
    }
}
