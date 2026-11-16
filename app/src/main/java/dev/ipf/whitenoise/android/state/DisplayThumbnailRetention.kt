package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.media.DisplayThumbnailSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // A deleted-message sweep that failed earlier left its batch queued; retry it before the scan.
        sweepRemovedMessageDisplayThumbnails()
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

/**
 * Reads one bounded group batch and applies only successful native answers: a removal state removes the original
 * and its derivatives, and any other state without a retained body removes the derivatives alone. False when MDK was
 * unreadable, so the pass does not count as complete.
 */
private suspend fun WhiteNoiseAppState.reconcileDisplayThumbnailBatch(
    engine: MarmotInterface,
    account: String,
    groupIdHex: String,
    batch: List<Pair<DisplayThumbnailSource, AttachmentTransferRequest>>,
    stillOwned: () -> Boolean,
): Boolean {
    val resolved = batch.mapNotNull { (source, request) -> resolveNativeAttachmentTarget(request)?.let(source::to) }
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
    states?.forEachIndexed { index, item ->
        val source = resolved[index].first
        val removed = item.state.removesDisplayThumbnail()
        val unbacked =
            !removed &&
                withContext(Dispatchers.IO) {
                    !item.state.keepsDisplayThumbnail(hostHoldsOriginal(source.attachmentKey))
                }
        if (removed || unbacked) evictRemovedDisplaySource(source, tokens[index], stillOwned, unbacked)
    }
    return states != null
}

/**
 * Checks the snapshot owner before destructive cache work and fences all pending producers of that source. With
 * [derivativesOnly], the original caches are left alone and only the display thumbnails go.
 */
private suspend fun WhiteNoiseAppState.evictRemovedDisplaySource(
    source: DisplayThumbnailSource,
    token: dev.ipf.whitenoise.android.media.DisplayThumbnailToken,
    stillOwned: () -> Boolean,
    derivativesOnly: Boolean = false,
) {
    withContext(NonCancellable) {
        val accepted =
            withContext(Dispatchers.Main.immediate) {
                (stillOwned() && displayThumbnails.isCurrent(token)).also { current ->
                    if (current && !derivativesOnly) removeMediaMemoryCacheEntry(source.attachmentKey)
                }
            }
        if (accepted) {
            withContext(Dispatchers.IO) {
                when {
                    !stillOwned() -> Unit
                    derivativesOnly -> displayThumbnails.remove(source.attachmentKey)
                    else -> removeMediaDiskCacheEntry(source.attachmentKey)
                }
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
