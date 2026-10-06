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
 * Shared local group wipe used by chat-list Delete and sole-member Leave flows.
 * The engine drops its own rows/secrets, but Android owns decrypted media caches
 * and tray notifications, so clear those before the group references disappear.
 */
internal suspend fun WhiteNoiseAppState.deleteGroupLocalWithClientCleanup(
    account: String,
    groupIdHex: String,
) {
    conversationDictation.onTargetRemoved(account, groupIdHex)
    evictGroupMediaCaches(account, groupIdHex)
    deleteDraftBeforeGroupRemoval(account, groupIdHex)
    withRevokedPinnedTarget(
        accountRef = account,
        groupIdHex = groupIdHex,
    ) {
        marmotIo { deleteGroupLocal(account, groupIdHex) }
        removeRevokedPinnedConversationShortcuts(account, groupIdHex)
        removeComposerExpansionForGroup(account, groupIdHex)
        dismissConversationNotifications(account, groupIdHex)
    }
}

/** The recoverable wipe preserves client data until commit; launcher authority is revoked before the native attempt. */
internal suspend fun WhiteNoiseAppState.deleteChatGroupLocalWithRecovery(
    account: String,
    groupIdHex: String,
    isCurrent: () -> Boolean,
    readinessBudget: LocalGroupDeleteReadinessBudget,
    onNativeCommitted: () -> Unit,
): Boolean =
    localGroupDeleteCleanupMutex.withLock {
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
                        ) { marmotIo { deleteGroupLocal(account, groupIdHex) } }
                    },
                ),
            )
        onNativeCommitted()
        if (pending != null) {
            completeLocalGroupDeleteCleanup(pending, attempt)
        } else {
            true
        }
    }

private suspend fun WhiteNoiseAppState.completeLocalGroupDeleteCleanup(
    pending: PendingLocalGroupDeleteCleanup,
    attempt: LocalGroupDeleteAttempt,
) = withContext(NonCancellable) {
    var cleanupFailure: Throwable? = null
    val result =
        runCatching {
            attempt.phase(LocalDeletePhase.PostCommitCleanup) {
                val complete =
                    finishLocalGroupDeleteCleanup(pending) { failure ->
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
                        withContext(NonCancellable) { finishLocalGroupDeleteCleanup(it) }
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

    cleanupStep("launcher shortcuts") { removePinnedConversationShortcuts(account, groupIdHex) }
    cleanupStep("dictation") { conversationDictation.onTargetRemoved(account, groupIdHex) }
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
                pending.mediaCacheKeys.forEach { diskMediaCache.remove(it) }
                if (pending.ciphertextTags.isNotEmpty()) diskMediaCache.removeByCiphertextTags(pending.ciphertextTags)
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
        cacheKeys.forEach { diskMediaCache.remove(it) }
        if (tags.isNotEmpty()) diskMediaCache.removeByCiphertextTags(tags)
    }
}

/**
 * MDK 0.10.0 `forgetGroupLocal`: erases this device's history and protocol state for the group without
 * sending a leave or disband, so the account rejoins only through a Welcome whose authenticated timestamp
 * is strictly newer than the reset. Android owns decrypted media caches, drafts, dictation targets and
 * tray notifications, so those are cleared before the group's rows disappear, mirroring the local delete.
 *
 * Returns false when MDK was already waiting for a fresh Welcome for this group.
 */
internal suspend fun WhiteNoiseAppState.forgetGroupLocalWithClientCleanup(
    account: String,
    groupIdHex: String,
): Boolean {
    conversationDictation.onTargetRemoved(account, groupIdHex)
    evictGroupMediaCaches(account, groupIdHex)
    deleteDraftBeforeGroupRemoval(account, groupIdHex)
    return withRevokedPinnedTarget(account, groupIdHex) {
        val reset = marmotIo { forgetGroupLocal(account, groupIdHex) }
        removeRevokedPinnedConversationShortcuts(account, groupIdHex)
        removeComposerExpansionForGroup(account, groupIdHex)
        dismissConversationNotifications(account, groupIdHex)
        reset
    }
}
