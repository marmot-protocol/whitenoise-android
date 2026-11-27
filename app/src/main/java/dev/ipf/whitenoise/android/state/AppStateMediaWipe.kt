package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.clearInactivePlaybackAndCheckRemaining
import dev.ipf.whitenoise.android.media.discardSharedPlaintextWipe
import dev.ipf.whitenoise.android.media.recordSharedPlaintextWipe
import dev.ipf.whitenoise.android.media.wipeSessionAttachmentPlaintext
import dev.ipf.whitenoise.android.media.wipeSharedPlaintext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fails closed before native deletion if Android cannot persist the share cleanup obligation. */
internal suspend fun WhiteNoiseAppState.prepareSharedPlaintextWipe(
    accountRef: String,
    retireSession: Boolean,
): Boolean =
    runCatchingCancellable {
        recordSharedPlaintextWipe(appContext, accountRef, retireSession)
    }.getOrDefault(false)

/** Keeps the staged files when a definitive native outcome leaves the owning account intact. */
internal suspend fun WhiteNoiseAppState.discardRejectedSharedPlaintextWipe(
    accountRef: String,
    retireSession: Boolean,
) {
    val discarded =
        runCatchingCancellable {
            discardSharedPlaintextWipe(appContext, accountRef, retireSession)
        }.getOrDefault(false)
    if (!discarded) appStateDebug { "could not disarm rejected shared plaintext wipe" }
}

/**
 * Wipes the session plaintext caches. Only a destructive wipe also ends FileProvider access to
 * previously shared files, so ordinary sign-out and leaving a chat preserve active external readers.
 */
internal suspend fun WhiteNoiseAppState.wipeDecryptedMediaFromDisk(
    wipedAccountRef: String? = null,
    retireSession: Boolean = true,
): WipeFailureItem? =
    withContext(NonCancellable + Dispatchers.IO) {
        var failed = false
        var retained = false
        val onFailure: (String, Throwable) -> Unit = { area, failure ->
            failed = true
            appStateDebug { "$area cleanup failed: ${failure.javaClass.simpleName}" }
        }
        if (retireSession) {
            AttachmentCachePublication.onWipeStarted()
            try {
                val reportDiskFailure: (Throwable) -> Unit = { onFailure("disk media cache", it) }
                runCatching { clearMediaDiskCaches(reportDiskFailure) }.onFailure(reportDiskFailure)
                wipeSessionAttachmentPlaintext(appContext.cacheDir, onFailure)
                if (wipedAccountRef == null) {
                    runCatching { forwardRequestPersistence.clear() }
                        .onFailure { onFailure("pending forward request", it) }
                }
            } finally {
                AttachmentCachePublication.onWipeFinished()
            }
        } else if (wipedAccountRef != null) {
            retained =
                runCatching { clearInactivePlaybackAndCheckRemaining(appContext.cacheDir) }
                    .onFailure { onFailure("unattributed media", it) }
                    .getOrDefault(true)
        }
        if (wipedAccountRef != null) {
            runCatching { check(displayAvatars.clearAccount(wipedAccountRef)) }
                .onFailure { onFailure("display avatar cache", it) }
            runCatching { forwardRequestPersistence.removeForAccount(wipedAccountRef) }
                .onFailure { onFailure("pending forward request", it) }
            wipeSharedPlaintext(appContext, wipedAccountRef, retireSession, onFailure)
        }
        val reasons =
            listOfNotNull(
                appContext.getString(R.string.wipe_media_cleanup_failed).takeIf { failed },
                appContext.getString(R.string.wipe_media_ownership_unknown).takeIf { retained },
            )
        reasons.takeIf { it.isNotEmpty() }?.let { WipeFailureItem(null, it.joinToString("\n")) }
    }

/** Drops the removed profile’s preview consent and releases its private share and editor leases. */
internal suspend fun WhiteNoiseAppState.purgeWipedAccountSources(
    wipedRef: String,
    wipedShareAccount: String?,
) {
    wipedShareAccount?.let { id ->
        publicJpegPreviewPreferences.removeAccount(id)
        publicJpegPreviewLoader.clearPixels(id)
    }
    withContext(NonCancellable + Dispatchers.IO) {
        wipedShareAccount?.takeIf(String::isNotBlank)?.let { account ->
            runCatching {
                val files =
                    dev.ipf.whitenoise.android.share
                        .PrivateShareFiles(appContext)
                files.leases.releaseAccount(account)
            }.onFailure {
                appStateDebug(it) { "private share purge failed after wipe: ${it.readableMessage()}" }
            }
        }
        runCatching {
            if (editorSessionStore.removeAccount(wipedRef)) {
                editorSessionStore.sourceLeaseReferenceCounts()?.let(editorSourceStore::reconcile)
            }
        }.onFailure {
            appStateDebug(it) { "editor purge failed after wipe: ${it.readableMessage()}" }
        }
    }
}

/** Signs out the departing profile's avatar cache alongside the existing media cleanup. */
internal suspend fun WhiteNoiseAppState.wipeSignedOutMedia(accountRef: String) {
    withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            if (accounts.any { it.label != accountRef && !it.signedOut }) {
                check(displayAvatars.clearAccount(accountRef))
            } else {
                displayAvatars.clearAll()
            }
        }.onFailure { failure ->
            appStateDebug { "display avatar cleanup failed: ${failure.javaClass.simpleName}" }
        }
    }
    wipeDecryptedMediaFromDisk()
}

/** Replays deferred derivative cleanup only for a successful, still-current native inventory. */
internal fun WhiteNoiseAppState.scheduleDisplayAvatarAccountReconciliation(
    accounts: List<AccountSummaryFfi>,
    accountListToken: Long,
) {
    val active = accounts.filterNot(AccountSummaryFfi::signedOut).mapTo(mutableSetOf(), AccountSummaryFfi::label)
    mutationsScope.launch(Dispatchers.IO) {
        runCatchingCancellable {
            displayAvatars.retainAccounts(active) { retire ->
                accountListLifetime.runIfCurrent(accountListToken, retire)
            }
        }.onFailure {
            appStateDebug { "display avatar account reconciliation failed: ${it.javaClass.simpleName}" }
        }
    }
}
