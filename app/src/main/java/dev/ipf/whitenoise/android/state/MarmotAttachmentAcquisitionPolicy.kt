package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.core.MarmotClient

/**
 * Leaves attachment acquisition under White Noise's network matrix, WorkManager queue, and encrypted cache.
 * MarmotKit 0.10.3 enables its independent automatic downloader by default, so preserving that default would
 * bypass the app's stop controls and duplicate retained media.
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

/** Disables MarmotKit's parallel automatic downloader without changing its quota and reserve values. */
internal suspend fun MarmotInterface.enforceAppOwnedAttachmentAcquisitionPolicy(accountRefs: Iterable<String>) {
    enforceAppOwnedAttachmentAcquisitionPolicy(
        accountRefs = accountRefs,
        readPolicy = { accountRef -> attachmentDownloadPolicy(accountRef) },
        writePolicy = { accountRef, policy -> setAttachmentDownloadPolicy(accountRef, policy) },
    )
}

/** Applies the app-owned acquisition policy to every account before the native runtime starts. */
internal suspend fun MarmotInterface.enforceAppOwnedAttachmentAcquisitionForKnownAccounts() {
    enforceAppOwnedAttachmentAcquisitionPolicy(listAccounts().map(AccountSummaryFfi::label))
}

/** Returns the native account snapshot only after every discovered account has the app-owned policy. */
internal suspend fun MarmotInterface.listAccountsWithAppAttachmentPolicy(): List<AccountSummaryFfi> {
    val accounts = listAccounts()
    enforceAppOwnedAttachmentAcquisitionPolicy(accounts.map(AccountSummaryFfi::label))
    return accounts
}

/** Creates an identity with the app-owned attachment policy installed before it can be activated. */
internal suspend fun MarmotInterface.createIdentityWithAppOwnedAttachmentAcquisition(): AccountSummaryFfi {
    val relays = MarmotClient.bootstrapRelays
    val account = createIdentity(relays, relays)
    enforceAppOwnedAttachmentAcquisitionPolicy(listOf(account.label))
    return account
}
