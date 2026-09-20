package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.core.MarmotClient

/**
 * Leaves automatic attachment acquisition under White Noise's network matrix, durable queue, and stop controls.
 * MarmotKit 0.10.3 enables a second automatic downloader by default, so only that flag is disabled while native
 * retention, reserve, and transfer limits remain unchanged for future canonical-transfer adoption.
 */
internal suspend fun enforceAppOwnedAttachmentAcquisitionPolicy(
    accountRefs: Iterable<String>,
    readPolicy: suspend (String) -> AttachmentDownloadPolicyFfi,
    writePolicy: suspend (String, AttachmentDownloadPolicyFfi) -> Unit,
) {
    accountRefs.distinct().forEach { accountRef ->
        val current = readPolicy(accountRef)
        if (current.automatic) writePolicy(accountRef, current.copy(automatic = false))
    }
}

/** Disables MarmotKit's parallel automatic downloader without changing its native numeric limits. */
internal suspend fun MarmotInterface.enforceAppOwnedAttachmentAcquisitionPolicy(accountRefs: Iterable<String>) {
    enforceAppOwnedAttachmentAcquisitionPolicy(
        accountRefs = accountRefs,
        readPolicy = { accountRef -> attachmentDownloadPolicy(accountRef) },
        writePolicy = { accountRef, policy -> setAttachmentDownloadPolicy(accountRef, policy) },
    )
}

/** Applies containment to every existing account after the native runtime reports local readiness. */
internal suspend fun MarmotInterface.enforceAppOwnedAttachmentAcquisitionForKnownAccounts() {
    enforceAppOwnedAttachmentAcquisitionPolicy(listAccounts().map(AccountSummaryFfi::label))
}

/** Returns an account snapshot only after every discovered account has the containment policy. */
internal suspend fun MarmotInterface.listAccountsWithAppAttachmentPolicy(): List<AccountSummaryFfi> {
    val accounts = listAccounts()
    enforceAppOwnedAttachmentAcquisitionPolicy(accounts.map(AccountSummaryFfi::label))
    return accounts
}

/** Creates an identity and installs containment before the Android account model can activate it. */
internal suspend fun MarmotInterface.createIdentityWithAppOwnedAttachmentAcquisition(): AccountSummaryFfi {
    val relays = MarmotClient.bootstrapRelays
    val account = createIdentity(relays, relays)
    enforceAppOwnedAttachmentAcquisitionPolicy(listOf(account.label))
    return account
}
