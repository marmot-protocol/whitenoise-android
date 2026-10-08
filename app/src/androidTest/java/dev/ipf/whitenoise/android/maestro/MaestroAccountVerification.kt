package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Account cancellation, scanner/lock checks and public-key copy must retain original identities and histories. */
internal fun requiresMaestroAccountRetentionProof(postcondition: String?): Boolean =
    postcondition in setOf("accounts-retained", "camera-denied", "app-lock-unavailable", "global-library-empty") ||
        postcondition?.startsWith("app-lock-credential-") == true ||
        requiresMaestroKeyRetentionProof(postcondition)

private fun requiresMaestroKeyRetentionProof(postcondition: String?): Boolean =
    postcondition?.startsWith("public-key-copy-") == true || postcondition?.startsWith("private-key-copy-") == true

/** Capture the same original native message for mutations and account-preserving platform journeys. */
internal fun requiresMaestroMessageBaseline(postcondition: String): Boolean =
    postcondition.startsWith("message-") || requiresMaestroAccountRetentionProof(postcondition)

/** A retained-account case must preserve the original native identities, session and both histories. */
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
    val expectedActive =
        if (postcondition == "private-key-copy-peer") originalMessage.peer else originalMessage.account
    withTimeout(15_000L) {
        check(expectedAccountIds.size == 3)
        withContext(Dispatchers.Main.immediate) {
            check(app.activeAccountRef == expectedActive)
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
