package dev.ipf.whitenoise.android.state

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

internal val REMOVED_DISPLAY_THUMBNAIL_SOURCES =
    setOf(
        AttachmentTransferStateFfi.UNAVAILABLE,
        AttachmentTransferStateFfi.REMOVED,
        AttachmentTransferStateFfi.CLEANED_BY_SETTING,
    )
private const val THUMBNAIL_RECONCILIATION_BATCH = 64

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
        for ((identity, group) in requests.groupBy { it.second.accountRef to it.second.groupIdHex }) {
            if (identity.first !in accounts) continue
            for (batch in group.chunked(THUMBNAIL_RECONCILIATION_BATCH)) {
                currentCoroutineContext().ensureActive()
                if (!stillOwned()) return
                reconcileDisplayThumbnailBatch(engine, identity.first, identity.second, batch, stillOwned)
            }
        }
    } finally {
        displayThumbnailReconciliationMutex.unlock()
    }
}

/** Reads one bounded group batch and applies only successful native removal answers. */
private suspend fun WhiteNoiseAppState.reconcileDisplayThumbnailBatch(
    engine: MarmotInterface,
    account: String,
    groupIdHex: String,
    batch: List<Pair<DisplayThumbnailSource, AttachmentTransferRequest>>,
    stillOwned: () -> Boolean,
) {
    val resolved =
        batch.mapNotNull { (source, request) ->
            runCatchingCancellable { resolveNativeAttachmentTarget(request) }.getOrNull()?.let { source to it }
        }
    if (resolved.isEmpty() || !stillOwned()) return
    val tokens = resolved.map { displayThumbnails.captureGeneration(it.first) }
    val states =
        runCatchingCancellable {
            withContext(Dispatchers.IO) {
                engine.attachmentTransferSnapshot(account, groupIdHex, resolved.map { it.second.toFfi() })
            }
        }.getOrNull()?.items ?: return
    if (states.size != resolved.size) return
    resolved.indices.filter { states[it].state in REMOVED_DISPLAY_THUMBNAIL_SOURCES }.forEach { index ->
        evictRemovedDisplaySource(resolved[index].first, tokens[index], stillOwned)
    }
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
    val prefix = mediaCacheKey(account, groupIdHex, messageIdHex, 0).dropLast(1)
    withContext(NonCancellable) {
        runCatching {
            withContext(Dispatchers.Main.immediate) {
                mediaMemoryCacheKeysSnapshot()
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                    .forEach(::removeMediaMemoryCacheEntry)
            }
            withContext(Dispatchers.IO) { displayThumbnails.removeMessage(prefix) }
        }.onFailure { android.util.Log.w("DisplayThumbnailCache", "deleted_message_cleanup_failed") }
    }
}

/** Recovers only the cache's own encrypted source descriptor; native state remains authoritative. */
internal fun DisplayThumbnailSource.transferRequest(): AttachmentTransferRequest? {
    val fields = attachmentKey.split('|')
    if (fields.size < 4) return null
    val index = fields.last().toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return AttachmentTransferRequest(
        fields.dropLast(3).joinToString("|"),
        fields[fields.size - 3],
        fields[fields.size - 2],
        index,
        sourceMessageIdHex,
    )
}
