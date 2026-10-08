package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Scanner/capture denial must preserve identity/history just as cancellation of account dialogs must. */
internal fun requiresMaestroAccountRetentionProof(postcondition: String?): Boolean =
    postcondition in setOf("accounts-retained", "camera-denied", "app-lock-unavailable") ||
        postcondition?.startsWith("app-lock-credential-") == true

/** Cancelled account actions must preserve the original native identities, session and both histories. */
internal suspend fun verifyMaestroAccountsRetained(
    native: Marmot,
    state: WhiteNoiseAppState?,
    baseline: MaestroMessageBaseline?,
    expectedAccountIds: Set<String>,
    postcondition: String?,
) {
    if (!requiresMaestroAccountRetentionProof(postcondition)) return
    val app = checkNotNull(state)
    val originalMessage = checkNotNull(baseline)
    withTimeout(15_000L) {
        check(expectedAccountIds.size == 3)
        withContext(Dispatchers.Main.immediate) {
            check(app.activeAccountRef == originalMessage.account)
            check(!app.signOutInProgress)
            check(!app.wipeInProgress)
            check(app.accounts.map { it.accountIdHex }.toSet() == expectedAccountIds)
        }
        check(native.listAccounts().map { it.accountIdHex }.toSet() == expectedAccountIds)
        for (account in listOf(originalMessage.account, originalMessage.peer)) {
            checkNotNull(native.presentedChatListRow(account, originalMessage.group)) {
                "Cancelled account action removed its original conversation"
            }
            val original =
                readMaestroMessages(native, account, originalMessage.group).singleOrNull {
                    it.messageIdHex == originalMessage.messageId
                }
            checkNotNull(original) { "Cancelled account action removed the original message" }
            check(!original.deleted)
            check(original.plaintext == "Generated fixture message")
        }
    }
}
