package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    marmotIo { deleteGroupLocal(account, groupIdHex) }
    removeComposerExpansionForGroup(account, groupIdHex)
    dismissConversationNotifications(account, groupIdHex)
}

/** The chat-list's recoverable local wipe must not change Android-owned state before commit. */
internal suspend fun WhiteNoiseAppState.deleteChatGroupLocalWithRecovery(
    account: String,
    groupIdHex: String,
    isCurrent: () -> Boolean,
    onNativeCommitted: () -> Unit,
) {
    // Capture encrypted media identifiers while the native group is still present. Unlike the
    // legacy leave/reset paths, a failed preflight aborts without touching the group or caches.
    val media =
        retryIdempotentRuntimeMutation {
            if (!isCurrent()) throw CancellationException("chat binding changed during local deletion preflight")
            marmotIo { listMedia(account, groupIdHex, null) }
        }
    val cacheKeys =
        media.map { rec ->
            mediaCacheKey(account, groupIdHex, rec.messageIdHex, rec.attachmentIndex.toInt())
        }
    val tags = media.mapNotNull { it.reference.ciphertextSha256 }.toSet()

    val pending = PendingLocalGroupDeleteCleanup(account, groupIdHex, cacheKeys, tags)
    localGroupDeleteCleanupMutex.withLock {
        // A synchronous, atomic journal write must precede the destructive native call. If a
        // closed worker loses both its response and the reconciliation reads, restart can replay
        // Android cleanup without ever repeating the native wipe.
        withContext(Dispatchers.IO) { localGroupDeleteCleanupJournal.stage(pending) }
        deleteLocalGroupWithRecovery(
            isCurrent = isCurrent,
            delete = { marmotIo { deleteGroupLocal(account, groupIdHex) } },
            isGroupPresent = { nativeGroupPresent(account, groupIdHex) },
        )
        onNativeCommitted()
        withContext(NonCancellable) {
            if (finishLocalGroupDeleteCleanup(pending)) {
                runCatching { withContext(Dispatchers.IO) { localGroupDeleteCleanupJournal.finish(pending) } }
                    .onFailure { appStateDebug(it) { "local delete cleanup journal finish failed" } }
            }
        }
    }
}

/** Replayed on startup and live refresh; a present group or failed read never clears client data. */
internal suspend fun WhiteNoiseAppState.reconcilePendingLocalGroupDeleteCleanups() {
    localGroupDeleteCleanupMutex.withLock {
        localGroupDeleteCleanupJournal.pending().forEach { pending ->
            runCatchingCancellable {
                reconcilePendingLocalGroupDeleteCleanup(
                    pending = pending,
                    accountReady = {
                        accounts.any { it.label == pending.account && it.isSignedInSigningAccount() }
                    },
                    isGroupPresent = { nativeGroupPresent(pending.account, pending.groupIdHex) },
                    cleanup = { withContext(NonCancellable) { finishLocalGroupDeleteCleanup(it) } },
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
    // The durable chat-list projection is the same native source that supplied the row being
    // removed. Unlike groupDetails, it does not depend on a live MLS roster.
    marmotIo { chatList(account, true) }
        .any { it.groupIdHex.equals(groupIdHex, ignoreCase = true) }

/** Returns false if any cleanup step failed so the durable intent can be retried later. */
private suspend fun WhiteNoiseAppState.finishLocalGroupDeleteCleanup(pending: PendingLocalGroupDeleteCleanup): Boolean {
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
                appStateDebug(failure) { "local delete $name cleanup failed" }
            }
    }

    cleanupStep("dictation") { conversationDictation.onTargetRemoved(account, groupIdHex) }
    if (pending.mediaCacheKeys.isNotEmpty()) {
        cleanupStep("memory media") {
            removeMediaMemoryCacheKeys(pending.mediaCacheKeys, Dispatchers.Main.immediate, ::removeMediaMemoryCacheEntry)
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
    val reset = marmotIo { forgetGroupLocal(account, groupIdHex) }
    removeComposerExpansionForGroup(account, groupIdHex)
    dismissConversationNotifications(account, groupIdHex)
    return reset
}
