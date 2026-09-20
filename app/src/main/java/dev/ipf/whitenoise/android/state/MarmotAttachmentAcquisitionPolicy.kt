package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.core.MarmotClient

/**
 * Enables the native worker in a HOST_MANAGED runtime, which starts denied and never discovers attachments
 * automatically. Android supplies demand and per-account network permissions; Marmot owns bytes and retries.
 * Migrates the 0.10.3 containment flag without changing retention, reserve, or transfer limits.
 */
internal suspend fun enforceAppOwnedAttachmentAcquisitionPolicy(
    accountRefs: Iterable<String>,
    readPolicy: suspend (String) -> AttachmentDownloadPolicyFfi,
    writePolicy: suspend (String, AttachmentDownloadPolicyFfi) -> Unit,
) {
    accountRefs.distinct().forEach { accountRef ->
        val current = readPolicy(accountRef)
        if (!current.automatic) writePolicy(accountRef, current.copy(automatic = true))
    }
}

/** Enables host-managed native demand without changing native numeric limits. */
internal suspend fun MarmotInterface.enforceAppOwnedAttachmentAcquisitionPolicy(accountRefs: Iterable<String>) {
    enforceAppOwnedAttachmentAcquisitionPolicy(
        accountRefs = accountRefs,
        readPolicy = { accountRef -> attachmentDownloadPolicy(accountRef) },
        writePolicy = { accountRef, policy -> setAttachmentDownloadPolicy(accountRef, policy) },
    )
}

/** Migrates existing accounts before runtime start, while host permission is still denied. */
internal suspend fun MarmotInterface.enforceAppOwnedAttachmentAcquisitionForKnownAccounts() {
    enforceAppOwnedAttachmentAcquisitionPolicy(listAccounts().map(AccountSummaryFfi::label))
}

/** Returns accounts after enabling host-managed demand for newly discovered identities. */
internal suspend fun MarmotInterface.listAccountsWithAppAttachmentPolicy(): List<AccountSummaryFfi> {
    val accounts = listAccounts()
    enforceAppOwnedAttachmentAcquisitionPolicy(accounts.map(AccountSummaryFfi::label))
    return accounts
}

/** Creates an identity with White Noise's full bootstrap relay set and returns its durable native receipt. */
internal suspend fun MarmotInterface.createIdentityWithBootstrapRelays(): AccountSummaryFfi {
    val relays = MarmotClient.bootstrapRelays
    return createIdentity(relays, relays)
}
