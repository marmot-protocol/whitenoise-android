package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.media.DisplayThumbnailSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * True when MDK holds no retained body for the source, so its derivative must not be read, written or kept.
 * Exhaustive on purpose: a state added by a future MarmotKit fails the build here instead of granting reads.
 */
internal fun AttachmentTransferStateFfi.removesDisplayThumbnail(): Boolean =
    when (this) {
        AttachmentTransferStateFfi.UNAVAILABLE,
        AttachmentTransferStateFfi.REMOVED,
        AttachmentTransferStateFfi.CLEANED_BY_SETTING,
        AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
        AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
        -> true
        AttachmentTransferStateFfi.NOT_REQUESTED,
        AttachmentTransferStateFfi.QUEUED,
        AttachmentTransferStateFfi.DOWNLOADING,
        AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT,
        AttachmentTransferStateFfi.DECRYPTING,
        AttachmentTransferStateFfi.VERIFYING_PLAINTEXT,
        AttachmentTransferStateFfi.READY,
        AttachmentTransferStateFfi.RETRY_SCHEDULED,
        AttachmentTransferStateFfi.FAILED,
        AttachmentTransferStateFfi.CANCELLED,
        AttachmentTransferStateFfi.PAUSED,
        AttachmentTransferStateFfi.POLICY_BLOCKED,
        AttachmentTransferStateFfi.RETRY_EXHAUSTED,
        AttachmentTransferStateFfi.STORAGE_BLOCKED,
        -> false
    }

private const val THUMBNAIL_RECONCILIATION_BATCH = 64
private const val CACHE_TARGET_COMPONENTS = 3
private const val FOREGROUND_RECONCILIATION_INTERVAL_MILLIS = 15 * 60 * 1000L

/** Requests cache maintenance outside rendering; concurrent lifecycle requests coalesce onto the current pass. */
internal fun WhiteNoiseAppState.scheduleDisplayThumbnailReconciliation(): Job {
    displayThumbnailReconciliationRequested = true
    displayThumbnailReconciliationJob?.takeIf { it.isActive }?.let { return it }
    return mutationsScope
        .launch {
            do {
                displayThumbnailReconciliationRequested = false
                runCatchingCancellable { reconcileDisplayThumbnails() }
                    .onFailure { android.util.Log.w("DisplayThumbnailCache", "reconciliation_failed") }
            } while (displayThumbnailReconciliationRequested)
        }.also { displayThumbnailReconciliationJob = it }
}

/** A foreground return reuses a recent completed pass, because each pass reads native state for every source. */
internal fun WhiteNoiseAppState.scheduleForegroundDisplayThumbnailReconciliation(): Job? {
    val completedAt = displayThumbnails.reconciledAtElapsedMillis
    val recent =
        completedAt != null &&
            SystemClock.elapsedRealtime() - completedAt < FOREGROUND_RECONCILIATION_INTERVAL_MILLIS
    return if (recent) null else scheduleDisplayThumbnailReconciliation()
}

/** Applies MDK's authoritative removal states across encrypted sources, including unopened conversations. */
internal suspend fun WhiteNoiseAppState.reconcileDisplayThumbnails() {
    displayThumbnailReconciliationMutex.lock()
    try {
        val engine = retentionSweepRuntimeOrNull() ?: return
        val owner = runtimeGeneration to mediaUploadSessionEpoch()
        val stillOwned = {
            retentionSweepRuntimeOrNull() === engine && owner == (runtimeGeneration to mediaUploadSessionEpoch())
        }
        val accounts = accounts.map { it.label }.toSet() + listOfNotNull(activeAccountRef)
        val sources = withContext(Dispatchers.IO) { displayThumbnails.storedSources() }
        val requests = sources.mapNotNull { source -> source.transferRequest()?.let { source to it } }
        var complete = true
        for ((identity, group) in requests.groupBy { it.second.accountRef to it.second.groupIdHex }) {
            if (identity.first !in accounts) continue
            for (batch in group.chunked(THUMBNAIL_RECONCILIATION_BATCH)) {
                currentCoroutineContext().ensureActive()
                if (!stillOwned()) return
                complete = reconcileDisplayThumbnailBatch(engine, identity.first, identity.second, batch, stillOwned) &&
                    complete
            }
        }
        if (complete) displayThumbnails.reconciledAtElapsedMillis = SystemClock.elapsedRealtime()
    } finally {
        displayThumbnailReconciliationMutex.unlock()
    }
}

/** Reads one bounded group batch and applies only successful native removal answers; false when MDK was unreadable. */
private suspend fun WhiteNoiseAppState.reconcileDisplayThumbnailBatch(
    engine: MarmotInterface,
    account: String,
    groupIdHex: String,
    batch: List<Pair<DisplayThumbnailSource, AttachmentTransferRequest>>,
    stillOwned: () -> Boolean,
): Boolean {
    val resolved =
        batch.mapNotNull { (source, request) ->
            runCatchingCancellable { resolveNativeAttachmentTarget(request) }.getOrNull()?.let { source to it }
        }
    if (resolved.isEmpty() || !stillOwned()) return true
    val tokens = resolved.map { displayThumbnails.captureGeneration(it.first) }
    val states =
        runCatchingCancellable {
            withContext(Dispatchers.IO) {
                engine.attachmentTransferSnapshot(account, groupIdHex, resolved.map { it.second.toFfi() })
            }
        }.getOrNull()
            ?.items
            ?.takeIf { it.size == resolved.size }
    states?.indices?.filter { states[it].state.removesDisplayThumbnail() }?.forEach { index ->
        evictRemovedDisplaySource(resolved[index].first, tokens[index], stillOwned)
    }
    return states != null
}

/** Checks the snapshot owner before destructive cache work and fences all pending producers of that source. */
private suspend fun WhiteNoiseAppState.evictRemovedDisplaySource(
    source: DisplayThumbnailSource,
    token: dev.ipf.whitenoise.android.media.DisplayThumbnailToken,
    stillOwned: () -> Boolean,
) {
    withContext(NonCancellable) {
        val accepted =
            withContext(Dispatchers.Main.immediate) {
                if (!stillOwned() || !displayThumbnails.isCurrent(token)) {
                    false
                } else {
                    removeMediaMemoryCacheEntry(source.attachmentKey)
                    true
                }
            }
        if (accepted) {
            withContext(Dispatchers.IO) {
                if (stillOwned()) removeMediaDiskCacheEntry(source.attachmentKey)
            }
        }
    }
}

/** Evicts deleted-message derivatives without depending on a loaded timeline or its former attachment count. */
internal suspend fun WhiteNoiseAppState.removeMessageDisplayThumbnails(
    account: String,
    groupIdHex: String,
    messageIdHex: String,
) {
    fenceMessageDisplayThumbnails(account, groupIdHex, messageIdHex)
    sweepRemovedMessageDisplayThumbnails()
}

/**
 * Retires pending producers and memory pixels and queues the disk sweep. It reads no disk, so it belongs inside the
 * group commit lock, while [sweepRemovedMessageDisplayThumbnails] runs after the lock is released.
 */
internal suspend fun WhiteNoiseAppState.fenceMessageDisplayThumbnails(
    account: String,
    groupIdHex: String,
    messageIdHex: String,
) {
    val prefix = mediaCacheKey(account, groupIdHex, messageIdHex, 0).dropLast(1)
    withContext(NonCancellable + Dispatchers.Main.immediate) {
        displayThumbnails.retireMessagePending(prefix)
        mediaMemoryCacheKeysSnapshot()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .forEach(::removeMediaMemoryCacheEntry)
        displayThumbnails.queueMessageRemoval(prefix)
    }
}

/** Removes every queued deleted message; deletes arriving together share one authenticated cache scan. */
internal suspend fun WhiteNoiseAppState.sweepRemovedMessageDisplayThumbnails() {
    withContext(NonCancellable + Dispatchers.IO) {
        runCatching { displayThumbnails.removeQueuedMessages() }
            .onFailure { android.util.Log.w("DisplayThumbnailCache", "deleted_message_cleanup_failed") }
    }
}

/**
 * Runs [deleteNative] and fences [target]'s derivatives under the commit lock, then sweeps them from disk without
 * holding it. The caller keeps the native call in view of its authorization guard.
 */
internal suspend fun ConversationController.deleteMessageForEveryone(
    account: String,
    target: String,
    deleteNative: suspend () -> Unit,
) {
    appState.withGroupCommitLock(account, group.groupIdHex) {
        deleteNative()
        appState.fenceMessageDisplayThumbnails(account, group.groupIdHex, target)
    }
    appState.mutationsScope.launch { appState.sweepRemovedMessageDisplayThumbnails() }
}

/** Recovers only the cache's own encrypted source descriptor; native state remains authoritative. */
internal fun DisplayThumbnailSource.transferRequest(): AttachmentTransferRequest? {
    val fields = attachmentKey.split('|')
    if (fields.size <= CACHE_TARGET_COMPONENTS) return null
    val (groupIdHex, messageIdHex, indexText) = fields.takeLast(CACHE_TARGET_COMPONENTS)
    return indexText.toIntOrNull()?.takeIf { it >= 0 }?.let { index ->
        AttachmentTransferRequest(
            fields.dropLast(CACHE_TARGET_COMPONENTS).joinToString("|"),
            groupIdHex,
            messageIdHex,
            index,
            sourceMessageIdHex,
        )
    }
}
