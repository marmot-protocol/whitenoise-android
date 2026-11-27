package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val LOCAL_GROUP_DELETE_RECONCILIATION_DELAYS_MS = listOf(0L, 5_000L, 30_000L)

/** Drops UI-only composer geometry when its owning conversation is explicitly removed. */
internal fun WhiteNoiseAppState.removeComposerExpansionForGroup(
    accountRef: String,
    groupIdHex: String,
) {
    composerExpansionStateRetention.removeGroup(accountRef, groupIdHex)
}

/** Schedule read-only replay after startup, live refresh or an uncertain native response. */
internal fun WhiteNoiseAppState.schedulePendingLocalGroupDeleteCleanup(retryTransport: Boolean = false) {
    if (!localGroupDeleteCleanupJournal.hasPending()) return
    mutationsScope.launch(Dispatchers.IO) {
        val delays = if (retryTransport) LOCAL_GROUP_DELETE_RECONCILIATION_DELAYS_MS else listOf(0L)
        for (pauseMillis in delays) {
            if (pauseMillis > 0) delay(pauseMillis)
            if (!localGroupDeleteCleanupJournal.hasPending()) break
            runCatchingCancellable { reconcilePendingLocalGroupDeleteCleanups() }
                .onFailure { appStateDebug(it) { "local group delete cleanup deferred" } }
        }
    }
}

/**
 * Group Details Delete and sole-member Leave share the pre-delete journal used by chat-list Delete.
 * A successful native removal can return even when Android cleanup needs startup replay.
 */
internal suspend fun WhiteNoiseAppState.deleteGroupLocalWithClientCleanup(
    account: String,
    groupIdHex: String,
) {
    val runtime = runtimeGeneration
    var committed = false
    try {
        deleteChatGroupLocalWithRecovery(
            LocalGroupRemovalRequest(account, groupIdHex, forgetProtocol = false, allowMissingMedia = true),
            isCurrent = {
                activeAccountRef == account && runtimeGeneration == runtime && !signOutInProgress && !wipeInProgress
            },
            readinessBudget = LocalGroupDeleteReadinessBudget(),
            onNativeCommitted = { committed = true },
        )
        check(committed) { "local deletion owner changed" }
    } finally {
        schedulePendingLocalGroupDeleteCleanup(retryTransport = true)
    }
}

/** One reviewed local removal. [onForgetResult] receives MDK's forget answer, false when already awaiting a Welcome. */
internal data class LocalGroupRemovalRequest(
    val account: String,
    val groupIdHex: String,
    val forgetProtocol: Boolean,
    val allowMissingMedia: Boolean = forgetProtocol,
    val onForgetResult: (Boolean) -> Unit = {},
) {
    /**
     * Abandonment and the live local removal path can proceed without media keys when the listing fails.
     * The journal still carries the group id for channel and shortcut cleanup.
     */
    fun mediaPreflightFallback(): ((Throwable) -> PendingLocalGroupDeleteCleanup)? =
        if (allowMissingMedia) {
            { failure ->
                appStateDebug(failure) { "local removal media listing failed, continuing without media keys" }
                PendingLocalGroupDeleteCleanup(account, groupIdHex, emptyList(), emptySet())
            }
        } else {
            null
        }
}

/**
 * Preserve client data until native commit, then retry trailing Android cleanup without another mutation.
 */
internal suspend fun WhiteNoiseAppState.deleteChatGroupLocalWithRecovery(
    request: LocalGroupRemovalRequest,
    isCurrent: () -> Boolean,
    readinessBudget: LocalGroupDeleteReadinessBudget,
    onAdmitted: () -> Unit = {},
    onNativeCommitted: () -> Unit,
): Boolean =
    localGroupDeleteCleanupMutex.withLock {
        if (!isCurrent()) return@withLock false
        onAdmitted()
        val account = request.account
        val groupIdHex = request.groupIdHex
        val captureIsCurrent = localDeleteCaptureGuard(account, groupIdHex)
        val attempt =
            LocalGroupDeleteAttempt(
                isCurrent = isCurrent,
                // MDK reconciles finished workers and waits for their replacements to be ready.
                recoverTransport = { marmotIo { catchUpAccounts() } },
                readinessBudget = readinessBudget,
            )
        val pending =
            stageAndDeleteLocalGroup(
                attempt,
                LocalGroupDeleteOperations(
                    previous = {
                        withContext(Dispatchers.IO) { localGroupDeleteCleanupJournal.find(account, groupIdHex) }
                    },
                    present = { nativeGroupPresent(account, groupIdHex) },
                    prepare = { prepareLocalGroupDeleteCleanup(account, groupIdHex) },
                    stage = { withContext(Dispatchers.IO) { localGroupDeleteCleanupJournal.stage(it) } },
                    delete = {
                        withRevokedPinnedTarget(
                            accountRef = account,
                            groupIdHex = groupIdHex,
                        ) {
                            attempt.checkCurrent()
                            if (request.forgetProtocol) {
                                request.onForgetResult(marmotIo { forgetGroupLocal(account, groupIdHex) })
                            } else {
                                marmotIo { deleteGroupLocal(account, groupIdHex) }
                            }
                        }
                    },
                    preflightFallback = request.mediaPreflightFallback(),
                ),
            )
        onNativeCommitted()
        if (pending != null) {
            completeLocalGroupDeleteCleanup(pending, attempt, captureIsCurrent)
        } else {
            true
        }
    }

/** Finishes immediate native commits under the same capture guard used by journal replay. */
private suspend fun WhiteNoiseAppState.completeLocalGroupDeleteCleanup(
    pending: PendingLocalGroupDeleteCleanup,
    attempt: LocalGroupDeleteAttempt,
    captureIsCurrent: () -> Boolean,
) = withContext(NonCancellable) {
    var cleanupFailure: Throwable? = null
    val result =
        runCatching {
            attempt.phase(LocalDeletePhase.PostCommitCleanup) {
                val complete =
                    finishLocalGroupDeleteCleanup(pending, captureIsCurrent) { failure ->
                        if (cleanupFailure == null) cleanupFailure = failure
                    }
                if (!complete) throw requireNotNull(cleanupFailure)
                withContext(Dispatchers.IO) { localGroupDeleteCleanupJournal.finish(pending) }
            }
        }.onFailure { failure ->
            // Native deletion is confirmed. Retain the intent and retry cleanup without routine notices.
            appStateDebug(failure) { "local delete client cleanup deferred" }
            schedulePendingLocalGroupDeleteCleanup(retryTransport = true)
        }
    result.isSuccess
}

private suspend fun WhiteNoiseAppState.prepareLocalGroupDeleteCleanup(
    account: String,
    groupIdHex: String,
): PendingLocalGroupDeleteCleanup {
    val media = marmotIo { listMedia(account, groupIdHex, null) }
    val cacheKeys =
        media.map { rec ->
            mediaCacheKey(account, groupIdHex, rec.messageIdHex, rec.attachmentIndex.toInt())
        }
    val tags = media.mapNotNull { it.reference.ciphertextSha256 }.toSet()
    return PendingLocalGroupDeleteCleanup(account, groupIdHex, cacheKeys, tags)
}

/** Replayed on startup and live refresh; a present group or failed read never clears client data. */
internal suspend fun WhiteNoiseAppState.reconcilePendingLocalGroupDeleteCleanups() {
    localGroupDeleteCleanupMutex.withLock {
        val runtime = runtimeGeneration
        localGroupDeleteCleanupJournal.pending().forEach { pending ->
            runCatchingCancellable {
                val captureIsCurrent = localDeleteCaptureGuard(pending.account, pending.groupIdHex)
                reconcilePendingLocalGroupDeleteCleanup(
                    pending = pending,
                    accountReady = {
                        val tearingDown = signOutInProgress || wipeInProgress
                        runtimeGeneration == runtime &&
                            !tearingDown &&
                            accounts.any { it.label == pending.account && it.isSignedInSigningAccount() }
                    },
                    isGroupPresent = { nativeGroupPresent(pending.account, pending.groupIdHex) },
                    cleanup = {
                        withContext(Dispatchers.Main.immediate) {
                            dismissLocalDeleteFailure(it.account, it.groupIdHex)
                        }
                        withContext(NonCancellable) { finishLocalGroupDeleteCleanup(it, captureIsCurrent) }
                    },
                    finish = localGroupDeleteCleanupJournal::finish,
                )
            }.onFailure { appStateDebug(it) { "local delete cleanup reconciliation deferred" } }
        }
    }
}

private suspend fun WhiteNoiseAppState.nativeGroupPresent(
    account: String,
    groupIdHex: String,
): Boolean =
    // The targeted durable row does not depend on a live roster or the loaded chat window.
    marmotIo { chatListRow(account, groupIdHex) } != null

/** Returns false if any cleanup step failed so the durable intent can be retried later. */
private suspend fun WhiteNoiseAppState.finishLocalGroupDeleteCleanup(
    pending: PendingLocalGroupDeleteCleanup,
    captureIsCurrent: () -> Boolean,
    onFailure: (Throwable) -> Unit = {},
): Boolean {
    val account = pending.account
    val groupIdHex = pending.groupIdHex
    var complete = true

    suspend fun cleanupStep(
        name: String,
        block: suspend () -> Unit,
    ) {
        runCatching { block() }
            .onFailure { failure ->
                complete = false
                onFailure(failure)
                appStateDebug(failure) { "local delete $name cleanup failed" }
            }
    }

    val captureCleanupAdmitted =
        withContext(Dispatchers.Main.immediate) {
            if (!captureIsCurrent()) return@withContext false
            cleanupStep("capture") {
                check(releaseCaptureTarget(account, groupIdHex)) { "Could not purge voice drafts for removed chat" }
            }
            true
        }
    if (!captureCleanupAdmitted) {
        onFailure(IllegalStateException("Local delete capture changed before cleanup"))
        return false
    }
    cleanupStep("launcher shortcuts") { removePinnedConversationShortcuts(account, groupIdHex) }
    if (pending.mediaCacheKeys.isNotEmpty()) {
        cleanupStep("memory media") {
            removeMediaMemoryCacheKeys(
                pending.mediaCacheKeys,
                Dispatchers.Main.immediate,
                ::removeMediaMemoryCacheEntry,
            )
        }
    }
    if (pending.mediaCacheKeys.isNotEmpty() || pending.ciphertextTags.isNotEmpty()) {
        cleanupStep("disk media") {
            withContext(Dispatchers.IO) {
                pending.mediaCacheKeys.forEach { removeMediaDiskCacheEntry(it) }
                if (pending.ciphertextTags.isNotEmpty()) removeMediaDiskCacheTags(pending.ciphertextTags)
            }
        }
    }
    cleanupStep("native draft") {
        retryIdempotentRuntimeMutation {
            when (val deletion = deleteDraftBeforeGroupRemoval(account, groupIdHex)) {
                is MessageDraftMutationResult.Success -> Unit
                is MessageDraftMutationResult.Failure -> throw deletion.cause
                else -> error("unexpected draft deletion result: $deletion")
            }
        }
    }
    cleanupStep("local draft") { draftStore.replaceFromAuthoritative(account, groupIdHex, null, null) }
    cleanupStep("composer expansion") { removeComposerExpansionForGroup(account, groupIdHex) }
    cleanupStep("notifications") { dismissConversationNotifications(account, groupIdHex) }
    return complete
}

internal suspend fun WhiteNoiseAppState.evictGroupMediaCaches(
    account: String,
    groupIdHex: String,
) {
    val media =
        runCatchingCancellable { marmotIo { listMedia(account, groupIdHex, null) } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return
    val cacheKeys =
        media.map { rec ->
            mediaCacheKey(account, groupIdHex, rec.messageIdHex, rec.attachmentIndex.toInt())
        }
    // ByteSizeLruCache is backed by a non-thread-safe LinkedHashMap. Keep the
    // in-memory L1 removals main-confined even though the disk L2 eviction below
    // correctly runs on IO.
    removeMediaMemoryCacheKeys(
        cacheKeys = cacheKeys,
        dispatcher = Dispatchers.Main.immediate,
        removeEntry = ::removeMediaMemoryCacheEntry,
    )
    val tags = media.mapNotNull { it.reference.ciphertextSha256 }.toSet()
    withContext(Dispatchers.IO) {
        cacheKeys.forEach { removeMediaDiskCacheEntry(it) }
        if (tags.isNotEmpty()) removeMediaDiskCacheTags(tags)
    }
}

/**
 * MDK 0.10.0 `forgetGroupLocal` behind the durable local-removal journal — the one owner of confirmed local
 * abandonment (#3310) for a DM or a group. It erases this account-device's history and protocol state and drops
 * the group's routing without sending a leave, admin change or disband, so it never waits on a peer. The account
 * rejoins only through a Welcome whose authenticated timestamp is strictly newer than the reset.
 *
 * The native forget runs at most once per confirmation. Android's media caches, drafts, dictation target, shortcuts
 * and tray cards are cleared after the commit from the staged journal entry, so a cleanup failure is retried from
 * the journal without replaying the forget, and an uncertain native outcome keeps its entry for reconciliation.
 *
 * Returns false when MDK was already waiting for a fresh Welcome for this group.
 */
internal suspend fun WhiteNoiseAppState.forgetGroupLocalWithClientCleanup(
    account: String,
    groupIdHex: String,
    isCurrent: () -> Boolean,
): Boolean {
    var reset = false
    runCatching {
        deleteChatGroupLocalWithRecovery(
            LocalGroupRemovalRequest(account, groupIdHex, forgetProtocol = true) { reset = it },
            isCurrent,
            LocalGroupDeleteReadinessBudget(),
            onNativeCommitted = {},
        )
    }.onFailure {
        schedulePendingLocalGroupDeleteCleanup(retryTransport = true)
        throw it
    }
    return reset
}
