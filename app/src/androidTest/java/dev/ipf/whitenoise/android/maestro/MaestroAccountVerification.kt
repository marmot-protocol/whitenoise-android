package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Cancelled account actions must preserve the original native identities, session and both histories. */
internal suspend fun verifyMaestroAccountsRetained(
    native: Marmot,
    state: WhiteNoiseAppState,
    baseline: MaestroMessageBaseline,
    expectedAccountIds: Set<String>,
) {
    withTimeout(15_000L) {
        check(expectedAccountIds.size == 3)
        withContext(Dispatchers.Main.immediate) {
            check(state.activeAccountRef == baseline.account)
            check(!state.signOutInProgress)
            check(!state.wipeInProgress)
            check(state.accounts.map { it.accountIdHex }.toSet() == expectedAccountIds)
        }
        check(native.listAccounts().map { it.accountIdHex }.toSet() == expectedAccountIds)
        for (account in listOf(baseline.account, baseline.peer)) {
            checkNotNull(native.presentedChatListRow(account, baseline.group)) {
                "Cancelled account action removed its original conversation"
            }
            val original = readMaestroMessages(native, account, baseline.group).singleOrNull {
                it.messageIdHex == baseline.messageId
            }
            checkNotNull(original) { "Cancelled account action removed the original message" }
            check(!original.deleted)
            check(original.plaintext == "Generated fixture message")
        }
    }
}
