package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSetupReadinessFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.MarmotClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

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

/**
 * Creates an identity and returns its durable native receipt. Its NIP-65 list declares
 * [MarmotClient.accountRelays]; publication and the inbox list use the full bootstrap relay set.
 */
internal suspend fun MarmotInterface.createIdentityWithBootstrapRelays(): AccountSummaryFfi {
    val relays = MarmotClient.bootstrapRelays
    return createIdentity(MarmotClient.accountRelays, relays, relays)
}

/**
 * Creates an identity on the same relays whose first public kind-0 is [profile], so contacts never see the
 * key-derived default name before the chosen one (mdk#2216). Unlike [createIdentityWithBootstrapRelays] it
 * returns at local readiness, so the caller retains the receipt before [awaitAccountNetworkReady].
 */
internal suspend fun MarmotInterface.createNamedIdentity(profile: UserProfileMetadataFfi): AccountSummaryFfi {
    val relays = MarmotClient.bootstrapRelays
    return createIdentityWithInitialProfile(profile, MarmotClient.accountRelays, relays, relays).account
}

/**
 * Polls native setup readiness until relay lists and the initial KeyPackage are published, while MDK's
 * background setup continues. Recovery and an expired wait surface as MDK's own typed setup errors.
 *
 * MDK stops background setup after a few quick attempts and resumes it only from `start()`. A retry passes
 * [resume], which runs once when the first read still shows local or publishing setup. Creation is never called
 * again to resume, because racing a just-finished setup would create a second account.
 */
internal suspend fun awaitAccountNetworkReady(
    accountRef: String,
    timeoutMillis: Long = ACCOUNT_NETWORK_READY_TIMEOUT_MILLIS,
    resume: (suspend () -> Unit)? = null,
    readiness: suspend (String) -> AccountSetupReadinessFfi,
) {
    var resumePending = resume != null
    withTimeoutOrNull(timeoutMillis) {
        while (true) {
            val state = readiness(accountRef)
            if (resumePending && state in RESUMABLE_SETUP) checkNotNull(resume).invoke()
            resumePending = false
            when (state) {
                AccountSetupReadinessFfi.NETWORK_READY -> return@withTimeoutOrNull
                AccountSetupReadinessFfi.RECOVERY_REQUIRED -> throw MarmotKitException.AccountSetupRecoveryRequired()
                else -> delay(ACCOUNT_NETWORK_READY_POLL_MILLIS)
            }
        }
    } ?: throw MarmotKitException.AccountSetupRetryRequired()
}

private val RESUMABLE_SETUP = setOf(AccountSetupReadinessFfi.LOCAL_READY, AccountSetupReadinessFfi.PUBLISHING)
private const val ACCOUNT_NETWORK_READY_TIMEOUT_MILLIS = 60_000L
private const val ACCOUNT_NETWORK_READY_POLL_MILLIS = 250L
