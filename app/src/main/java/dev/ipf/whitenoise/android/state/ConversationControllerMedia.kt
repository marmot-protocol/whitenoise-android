package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.state.ConversationController.Companion.HEX_MESSAGE_ID
import dev.ipf.whitenoise.android.state.ConversationController.Companion.albumExceedsRetainedCap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Rejects empty payloads before reserving a pending slot and reports the retained-byte limit for
 * in-memory items. A file-backed item cannot join a reply: native reply drafts carry attachment bytes.
 */
internal fun ConversationController.canQueueAttachmentBytes(
    attachments: List<PendingAttachment>,
    replyTarget: String?,
): Boolean {
    if (!canSendMessages || attachments.isEmpty() || attachments.any { !it.hasContent }) return false
    return if (albumExceedsRetainedCap(attachments)) {
        appState.present(R.string.media_album_too_large)
        false
    } else if (replyTarget != null && attachments.any { it.isFileBacked }) {
        appState.present(R.string.media_file_too_large)
        false
    } else {
        true
    }
}

/** Rechecks presentation eligibility and account generation after asynchronous preparation. */
internal fun ConversationController.canQueueMediaForAccount(
    account: String,
    canQueue: () -> Boolean,
): Boolean =
    canQueue() &&
        canSendMessages &&
        shouldAcceptMediaUploadForAccount(
            account,
            mediaUploadSessionEpoch,
            appState.activeAccountRef,
            appState.mediaUploadSessionEpoch(),
        )

/** Builds the optimistic pending record that carries staged attachments until the send confirms. */
internal suspend fun ConversationController.pendingAttachmentRecord(
    tempId: String,
    body: String,
    attachments: List<PendingAttachment>,
    now: ULong,
    replyTarget: String?,
): AppMessageRecordFfi =
    AppMessageRecordFfi(
        invalidated = false,
        messageIdHex = tempId,
        direction = "sent",
        groupIdHex = group.groupIdHex,
        sender = conversationAccountIdHex ?: "",
        plaintext = body,
        contentTokens = markdownParser(body),
        kind = 9uL,
        tags =
            attachments.map { MessageTagFfi(listOf("_media_pending", it.fileName, it.mediaType)) } +
                replyTarget?.let { listOf(MessageProjector.eventTag(it), MessageProjector.quoteTag(it)) }.orEmpty(),
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = now,
        receivedAt = now,
        sticker = null,
    )

/**
 * Shared upload→publish path for both first send and retry. Reads the
 * compressed bytes from [retainedMediaUploads] (keyed by [key]) so it
 * survives a Failed→retry round-trip. On success: drops the optimistic +
 * retained entry, then seeds [WhiteNoiseAppState.cacheMediaPlaintext] with the just-uploaded
 * plaintext so the sender renders its own image without a download. On
 * failure: flips back to Failed (bytes stay retained for another retry).
 */
internal suspend fun ConversationController.performMediaUpload(
    account: String,
    key: String,
    tempId: String,
    order: ULong,
    optimistic: AppMessageRecordFfi,
) {
    val sendHostAttempt = appState.beginHostPerformance(HostPerformanceOperationFfi.MESSAGE_SEND)
    val retentionAtSendSeconds = optimisticMessages[key]?.retentionAtSendSeconds
    val uploadJob = appState.trackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key)
    val diagnostics = appState.pendingSendDiagnostics
    try {
        if (
            !shouldAcceptMediaUploadForAccount(
                account,
                mediaUploadSessionEpoch,
                appState.activeAccountRef,
                appState.mediaUploadSessionEpoch(),
            )
        ) {
            discardOptimisticSend(key, tempId, OptimisticSendDiscard.ABANDONED)
            return
        }
        // queueAttachments and uploadQueued are intentionally separate so
        // a user can cancel the seeded bubble before this continuation is
        // scheduled. Honour that tombstone before consulting retained
        // bytes; otherwise their cancellation cleanup looks like an
        // eviction and resurrects the row as Failed.
        if (
            optimisticSendPhases[key] == OptimisticSendPhase.CANCELLED ||
            discardedDuringRetry.remove(key)
        ) {
            removeCancelledOptimisticSend(key, tempId)
            trimCancelledSendTombstones()
            return
        }
        val retained =
            retainedMediaUploads.get(key) ?: run {
                // Bytes are gone (evicted under cap, or process death) — can't
                // retry without a re-attach. Leave the bubble Failed and drop
                // the in-flight marker so a future dispose can clean up.
                optimisticMessages[key] =
                    TimelineMessage(
                        key,
                        optimistic,
                        MessageStatus.Failed,
                        timelineOrder = order,
                        retentionAtSendSeconds = retentionAtSendSeconds,
                    )
                optimisticSendPhases[key] = OptimisticSendPhase.FAILED
                activeUploadKeys.remove(key)
                failOptimisticChatListPreview(tempId)
                diagnostics.complete(
                    tempId,
                    PerformancePhase.SEND_FAILED,
                    PerformanceResult.FAILURE,
                )
                publishTimelineFromIndexes()
                appState.present(R.string.toast_reattach_to_retry_media)
                return
            }
        discardedDuringRetry.remove(key)
        // Every attempt reports its own transfer from the start, so a Retry never shows the last one's bytes.
        retained.clearTransferProgress()
        try {
            // Reuse the references if a prior attempt already uploaded the
            // blobs (publish-only failure) — re-uploading would orphan
            // duplicates on the Blossom server.
            val request =
                MediaUploadRequestFfi(
                    attachments =
                        retained.attachments.map { attachment ->
                            MediaUploadAttachmentRequestFfi(
                                fileName = attachment.fileName,
                                mediaType = attachment.mediaType,
                                plaintext = attachment.plaintextBytes,
                                dim = attachment.dim,
                                thumbhash = attachment.thumbhash,
                            )
                        },
                    caption = retained.caption,
                    send = false,
                    blossomServer = null,
                )
            val references =
                retained.uploadedReferences?.also { diagnostics.mediaUploadReused(tempId) }
                    ?: run {
                        val startedAtMs = diagnostics.beginMediaUpload(tempId)
                        val uploaded =
                            if (retained.isFileBacked || retained.isDraftStored) {
                                uploadFileBackedRetained(account, key, tempId, retained, startedAtMs)
                            } else {
                                mediaUploader?.invoke(account, group.groupIdHex, request)
                                    ?: appState
                                        .withGroupCommitLock(account, group.groupIdHex) {
                                            requireOptimisticSendNotCancelled(key)
                                            val outcome =
                                                appState.marmotIo(MarmotTraceSection.MEDIA_UPLOAD) {
                                                    uploadOrAdmitComposerMediaWithToken(
                                                        account,
                                                        group.groupIdHex,
                                                        request,
                                                        tempId,
                                                        forceUploadOnly = retained.uploadsBeforeAdmission,
                                                    )
                                                }
                                            outcome.acceptance?.let { recordOptimisticSendAcceptance(key, it) }
                                            outcome
                                        }.also { outcome ->
                                            outcome.captureForRetry(
                                                retained,
                                                diagnostics,
                                                tempId,
                                                startedAtMs,
                                            )
                                        }.upload
                            }.attachments.map { it.reference }
                        diagnostics.finishMediaUpload(tempId, startedAtMs)
                        uploaded
                            .also {
                                if (!retained.recoveredWithoutUpload && it.size != retained.attachments.size) {
                                    error(
                                        "media upload returned ${it.size} references for " +
                                            "${retained.attachments.size} attachments",
                                    )
                                }
                            }.also { retained.uploadedReferences = it }
                    }
            // Stop before publication after a discard; an unreferenced Blossom blob is inert.
            if (discardedDuringRetry.remove(key)) {
                discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_IN_FLIGHT)
                trimCancelledSendTombstones()
                return
            }
            val summary =
                retained.localAcceptance
                    ?: run {
                        val startedAtMs = diagnostics.beginMediaPublish(tempId)
                        appState
                            .withGroupCommitLock(account, group.groupIdHex) {
                                requireOptimisticSendNotCancelled(key)
                                val accepted =
                                    mediaPublisher?.invoke(account, group.groupIdHex, references, retained.caption)
                                        ?: admitRetainedMedia(account, references, retained, tempId)
                                recordOptimisticSendAcceptance(key, accepted)
                                accepted
                            }.also { diagnostics.finishMediaPublish(tempId, startedAtMs) }
                    }
            // Once MDK has accepted the send, no retry uploads again, so large snapshots can go now instead of
            // waiting, possibly offline for hours, for it to settle. Until then a stale-epoch refusal needs them.
            retained.releaseStagedSources(appState::closeStagedUploadSourceOffMain)
            completeDurableAcceptance(key)
            if (retained.replyTarget != null && replySelectionVersion == retained.replyVersion) replyingTo = null
            sendHostAttempt.success()
            val canonicalId = summary.messageIds.firstOrNull()
            diagnostics.alias(tempId, canonicalId)
            if (summary.acceptDisposition == SendAcceptDispositionFfi.ACCEPTED_PENDING) {
                // MDK now owns a durable, unpublished media intent. It still
                // returns the canonical app-event id, which lets a later
                // projection settle this exact bubble even when other media
                // sends are queued at the same time.
                val acceptedPendingMessageIdHex =
                    canonicalId?.takeIf(HEX_MESSAGE_ID::matches)
                diagnostics.recordAcceptedPending(tempId, acceptedPending = true)
                // A retry can be discarded while the FFI call is suspended.
                // MDK cannot retract an accepted intent, but the user chose
                // to discard this local bubble, so don't retain its bytes or
                // use the canonical id to restore a deferred projection.
                if (discardedDuringRetry.remove(key)) {
                    acceptedPendingMessageIdHex?.let(pendingProjectionsAwaitingBridge::remove)
                    discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_IN_FLIGHT)
                    trimCancelledSendTombstones()
                    return
                }
                // A single-media projection can beat the FFI return and
                // reconcile heuristically before its exact MDK id is known.
                // Once the return supplies that id, finish the same exact
                // chat-list handoff and release the retained upload state.
                val acceptedPendingProjectionAlreadyLanded =
                    acceptedPendingMessageIdHex != null &&
                        acceptedPendingMessageIdHex in projectedMessageIds
                if (acceptedPendingProjectionAlreadyLanded) {
                    val confirmedMessageIdHex = requireNotNull(acceptedPendingMessageIdHex)
                    // The early echo may have seeded only L1 before upload supplied the ciphertext tag.
                    // Register the guarded durable host copy before releasing its retained plaintext.
                    handoffOwnMediaCacheOnReconcile(key, confirmedMessageIdHex)
                    appState.commitOptimisticSentPreview(
                        accountRef = conversationAccountRef,
                        groupIdHex = group.groupIdHex,
                        optimisticMessageIdHex = tempId,
                        confirmedMessageIdHex = confirmedMessageIdHex,
                    )
                    optimisticMessages.remove(key)
                    messageById.remove(tempId)
                    retainedMediaUploads.remove(key)
                    activeUploadKeys.remove(key)
                    diagnostics.recordEchoReconcile(tempId)
                    publishTimelineFromIndexes()
                    optimisticSendPhases.remove(key)
                    return
                }
                retained.acceptedPending = true
                retained.acceptedPendingMessageIdHex = acceptedPendingMessageIdHex
                // The local projection can beat the FFI return. It was held
                // only because several media optimistics were indistinguishable
                // at that instant; now the MDK id gives it an exact owner.
                acceptedPendingMessageIdHex
                    ?.let(pendingProjectionsAwaitingBridge::get)
                    ?.let { deferredProjection ->
                        upsertProjectedRecord(
                            deferredProjection,
                            reconcileOptimistic = true,
                            allowDelayedProjection = true,
                        )
                    }
                publishTimelineFromIndexes()
                return
            }
            // MarmotKit owns the encrypted-media wire format. Build the
            // optimistic bridge tags through the same native API that
            // validates and publishes the projected attachments.
            val imetaTags = mediaImetaTagsBuilder(account, group.groupIdHex, references)
            val confirmedId = canonicalId ?: tempId
            diagnostics.mediaTransportComplete(tempId)
            transferRetentionAtSend(tempId, confirmedId)
            appState.commitOptimisticSentPreview(
                accountRef = conversationAccountRef,
                groupIdHex = group.groupIdHex,
                optimisticMessageIdHex = tempId,
                confirmedMessageIdHex = confirmedId,
            )
            // INVARIANT: the discard re-check must run BEFORE any cache mutation
            // below, so a mid-flight discard never seeds the just-sent bytes.
            if (discardedDuringRetry.remove(key)) {
                // User discarded after publish committed; drop the local
                // optimistic + bytes. The published event may still echo back
                // via projection (publish already succeeded — not retractable).
                discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_AFTER_PUBLISH)
                trimCancelledSendTombstones()
                return
            }
            // Seed the decrypted-bytes AND decoded-thumbnail caches under the
            // confirmed id so the sender's own bubble renders instantly — no
            // Blossom round-trip and no decode spinner. One cache entry per
            // attachment under `(account, messageId, attachmentIndex)`.
            //
            // Re-check the session/account immediately before seeding: the
            // upload + sendMediaAttachments above are long suspend points, and
            // a sign-out / account switch in that window runs
            // `clearInMemoryMediaCaches()` and bumps the upload-session epoch.
            // Without this guard, the in-memory L1 caches (which are NOT
            // generation-gated) would be repopulated with the just-signed-out
            // account's decrypted plaintext, surviving the sign-out clear until
            // the next `clearInMemoryMediaCaches()`. The L2 disk write below is
            // already generation-gated, but skipping it too on a stale session
            // keeps the behaviour consistent (it would no-op anyway). The bridge
            // insert below is intentionally left running: the publish already
            // committed, so the timeline state still needs reconciling.
            val sessionStillValid = mediaUploadSessionStillCurrent(account)
            if (confirmedId.isNotEmpty() && sessionStillValid) {
                retained.attachments.forEachIndexed { index, attachment ->
                    if (!mediaUploadSessionStillCurrent(account)) return@forEachIndexed
                    // MDK retains a file-backed item's own copy; only in-memory bytes seed the host cache.
                    val bytes = attachment.inMemoryBytes ?: return@forEachIndexed
                    val confirmedKey = mediaCacheKey(account, confirmedId, index)
                    appState.cacheUploadedAttachment(
                        confirmedKey,
                        bytes,
                        references.getOrNull(index)?.ciphertextSha256,
                    )
                    // Offload the multi-MB ARGB decode to Default; the
                    // main-confined thumbnail-cache put resumes on Main.
                    // Mirrors the receive/render path in WhiteNoiseApp.
                    // The attachment names its sent draft's domain, the one its previews used.
                    val decoded = mediaThumbnailDecoder(attachment, attachment.outgoingDomain)
                    if (!mediaUploadSessionStillCurrent(account)) return@forEachIndexed
                    if (decoded != null) {
                        appState.cacheMediaThumbnail(confirmedKey, decoded)
                    }
                }
            }
            // Keep the pending row available to exact native reconciliation across thumbnail suspension.
            // Removing it earlier lets a concurrent echo publish a new presentation identity.
            optimisticMessages.remove(key)
            messageById.remove(tempId)
            retainedMediaUploads.remove(key)
            activeUploadKeys.remove(key)
            // Bridge the gap until the published event echoes back via the
            // projection: insert a confirmed *image* optimistic carrying the
            // imeta tags (one per uploaded reference), keyed on confirmedId.
            // Same key as the eventual projected item, so the bubble never
            // disappears/reappears, and it renders from the seeded thumbnail.
            // pruneConfirmedOptimisticMessages reconciles it on arrival.
            val handoffHandled =
                if (confirmedId.isNotEmpty()) {
                    // Always insert the bridge. When the projection has already
                    // arrived (race-loser), `optimisticMessageIdForProjection`
                    // refuses to reconcile (no exact-id match + multiple
                    // `_media_pending` siblings → null), leaving the new
                    // projection alongside the still-pending optimistic until
                    // this bridge insert resolves the pairing via id collision
                    // in `publishTimelineFromIndexes`. The bridge carries the
                    // real imeta tags so it renders identically to the
                    // projection it eventually consumes.
                    val confirmedRecord =
                        optimistic.copy(
                            messageIdHex = confirmedId,
                            // Match what the published event carries (the caption we
                            // sent), not the "📎 filename" optimistic placeholder, so
                            // the bridge bubble is identical to the projected one.
                            plaintext = retained.caption.orEmpty(),
                            tags =
                                imetaTags +
                                    optimistic.tags.filter {
                                        it.values.firstOrNull() != "_media_pending"
                                    },
                            sourceEpoch = references.firstOrNull()?.sourceEpoch,
                        )
                    messageById[confirmedId] = confirmedRecord
                    optimisticMessages["msg:$confirmedId"] =
                        TimelineMessage(
                            "msg:$confirmedId",
                            confirmedRecord,
                            MessageStatus.Sent,
                            timelineOrder = order,
                            retentionAtSendSeconds = retentionAtSendSeconds,
                            presentationId = key,
                        )
                    // Register the bridge through the same tracked preserve path
                    // as text sends so orphan cleanup can release its overrides
                    // after the confirmed handoff. Drain any
                    // projection echo that arrived while this send was still
                    // mid-`sendMediaAttachments` — at that point the heuristic
                    // refused the match and the projection was stashed in
                    // `pendingProjectionsAwaitingBridge` to avoid a position-0
                    // render flip. Now that the overrides are stamped, the
                    // build below will produce a TimelineMessage at the right
                    // position.
                    preserveOptimisticDisplayPosition(confirmedId, confirmedId)
                    val deferredProjection = pendingProjectionsAwaitingBridge[confirmedId]
                    val deliveredToAttachedController =
                        appState.deliverConfirmedMediaHandoff(
                            accountRef = conversationAccountRef,
                            groupIdHex = group.groupIdHex,
                            confirmedId = confirmedId,
                            deferredProjection = deferredProjection,
                        )
                    // A controller used outside the composed screen is not in
                    // AppState's attached set. It may still consume its own
                    // projection, but a disposed upload owner must leave the
                    // shared bridge + stash for the next live controller.
                    deliveredToAttachedController ||
                        (
                            !controllerCleared &&
                                acceptConfirmedMediaHandoff(confirmedId, deferredProjection)
                        )
                } else {
                    false
                }
            // The accepting controller owns publication and settlement. A
            // disposed upload owner must not consume its shared preserve.
            if (!handoffHandled && !controllerCleared) {
                publishTimelineFromIndexes()
            }
            optimisticSendPhases.remove(key)
        } catch (throwable: Throwable) {
            // Coroutine cancellation (e.g. leaving the screen) is not a send
            // failure — rethrow so it isn't surfaced as a Failed bubble/toast.
            if (throwable is CancellationException) {
                sendHostAttempt.cancel()
                if (optimisticSendPhases[key] == OptimisticSendPhase.CANCELLED) {
                    discardedDuringRetry.remove(key)
                    trimCancelledSendTombstones()
                }
                throw throwable
            }
            if (throwable is OptimisticSendCancelledException) {
                sendHostAttempt.cancel()
                discardedDuringRetry.remove(key)
                removeCancelledOptimisticSend(key, tempId)
                trimCancelledSendTombstones()
                return
            }
            // Cancel stopped a file-backed transfer before admission: settle it as cancelled, not failed.
            if (retained.consumeTransferCancelRequest() && !optimisticSendIsMdkOwnedOrUnknown(key)) {
                sendHostAttempt.cancel()
                optimisticSendPhases[key] = OptimisticSendPhase.CANCELLED
                optimisticCancellationGeneration.value += 1
                discardedDuringRetry.remove(key)
                removeCancelledOptimisticSend(key, tempId)
                trimCancelledSendTombstones()
                return
            }
            if (discardedDuringRetry.remove(key)) {
                sendHostAttempt.cancel()
                discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_AFTER_MEDIA_FAILURE)
                trimCancelledSendTombstones()
                return
            }
            if (optimisticSendIsMdkOwnedOrUnknown(key)) {
                sendHostAttempt.unavailable()
                publishTimelineFromIndexes()
                if (BuildConfig.DEBUG) Log.w("DMConversation", "post-acceptance media settlement failed", throwable)
                return
            }
            // MDK refuses references whose upload epoch or branch is no longer current (mdk#2891), and
            // reusing them would fail every Retry the same way, so the next attempt uploads again.
            retained.forgetUploadAfter(throwable)
            // A failed bubble offers Retry, not the bytes of the attempt that failed.
            retained.clearTransferProgress()
            optimisticMessages[key] =
                TimelineMessage(
                    key,
                    optimistic,
                    MessageStatus.Failed,
                    timelineOrder = order,
                    retentionAtSendSeconds = retentionAtSendSeconds,
                )
            optimisticSendPhases[key] = OptimisticSendPhase.FAILED
            failOptimisticChatListPreview(tempId)
            // Failed bubble shown but bytes are retained for a possible
            // retry — KEEP the key in `activeUploadKeys` so a screen
            // dispose can't wipe the bytes out from under a retry tap.
            // The key drains when the user retries (terminal performMediaUpload
            // path runs) or explicitly discards.
            publishTimelineFromIndexes()
            if (BuildConfig.DEBUG) Log.w("DMConversation", "media upload failed", throwable)
            diagnostics.complete(
                tempId,
                PerformancePhase.SEND_FAILED,
                PerformanceResult.FAILURE,
            )
            sendHostAttempt.failure()
            presentSendFailure(appState, throwable, sendFailureAttempt(key))
        }
    } finally {
        sendHostAttempt.unavailable()
        appState.untrackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key, uploadJob)
    }
}

private fun ConversationController.mediaUploadSessionStillCurrent(account: String): Boolean =
    shouldAcceptMediaUploadForAccount(
        account,
        mediaUploadSessionEpoch,
        appState.activeAccountRef,
        appState.mediaUploadSessionEpoch(),
    )

// Evict decrypted media (L1 plaintext, decoded thumbnails, L2 disk) for the
// attachments the engine just secure-deleted on expiry, matched by ciphertext
// hash through the cached references. Without this the decrypted bytes stay
// recoverable from the local caches after the row is gone (#674 review).
internal suspend fun ConversationController.evictExpiredMediaCaches(
    account: String,
    expiredCiphertextSha256: Set<String>,
) {
    if (expiredCiphertextSha256.isEmpty()) return
    // Map expired hashes to cache keys via the loaded projected rows.
    // TimelineMessageRecordFfi.media is authoritative and already carries
    // each attachment's real source epoch, so this needs no group-wide
    // listMedia scan or parallel controller cache.
    val loadedKeys =
        timelineRecords.values.flatMap { record ->
            MessageAttachments.accepted(record.media).mapNotNull { (index, reference) ->
                if (reference.ciphertextSha256 in expiredCiphertextSha256) {
                    mediaCacheKey(account, record.messageIdHex, index)
                } else {
                    null
                }
            }
        }
    // The bounded window can't map rows it has already trimmed: any L1
    // key in this group's slice without a loaded or in-flight row is
    // dropped fail-closed alongside the precise hits, so a pruned old
    // attachment's decrypted bytes can't outlive the prune.
    val loadedMessageIds =
        buildSet {
            addAll(timelineRecords.keys)
            optimisticMessages.values.forEach { add(it.record.messageIdHex) }
            addAll(optimisticMessages.keys)
        }
    val staleKeys =
        withContext(Dispatchers.Main.immediate) {
            staleGroupMediaCacheKeys(
                cachedKeys = appState.mediaMemoryCacheKeysSnapshot(),
                account = account,
                groupIdHex = group.groupIdHex,
                loadedMessageIds = loadedMessageIds,
            )
        }
    val evictedKeys = loadedKeys + staleKeys
    removeMediaMemoryCacheKeys(
        cacheKeys = evictedKeys,
        dispatcher = Dispatchers.Main.immediate,
        removeEntry = appState::removeMediaMemoryCacheEntry,
    )
    withContext(Dispatchers.IO) {
        loadedKeys.forEach { appState.removeMediaDiskCacheEntry(it) }
        // Plus any disk entry stamped with an expired ciphertext tag — the
        // only path that reaches media whose message isn't currently loaded,
        // including across process restarts. #674 review.
        appState.removeMediaDiskCacheTags(expiredCiphertextSha256)
    }
}

/**
 * Race-fix for own-sent media bubbles: when an outgoing media projection
 * arrives and we reconcile it against the pending `_media_pending`
 * optimistic, we already hold the JPEG bytes in [retainedMediaUploads].
 * Seed L1 plaintext + decoded thumbnail under the projection's cache
 * key so the bubble's `LaunchedEffect` finds bytes immediately —
 * otherwise it would call `downloadAttachment` → empty L1 → empty L2
 * (background disk write hasn't flushed yet) → re-download from
 * Blossom, even though we just uploaded the same bytes.
 *
 * L2 (disk) write is scheduled in the background, same pattern as
 * `downloadAttachment`'s post-fetch path.
 */
internal fun ConversationController.handoffOwnMediaCacheOnReconcile(
    optimisticKey: String,
    projectedMessageIdHex: String,
) {
    val retained = retainedMediaUploads.get(optimisticKey) ?: return
    val account = conversationAccountRef?.takeIf(::mediaUploadSessionStillCurrent) ?: return
    // Seed every attachment under its own (messageId, attachmentIndex)
    // key so the projected album bubble's per-tile cache lookups all
    // hit immediately on reconcile.
    retained.attachments.forEachIndexed { index, attachment ->
        if (!mediaUploadSessionStillCurrent(account)) return@forEachIndexed
        // MDK retains a file-backed item's own copy; only in-memory bytes seed the host cache.
        val bytes = attachment.inMemoryBytes ?: return@forEachIndexed
        val cacheKey = mediaCacheKey(account, projectedMessageIdHex, index)
        appState.cacheUploadedAttachment(
            cacheKey,
            bytes,
            retained.uploadedReferences?.getOrNull(index)?.ciphertextSha256,
        )
        // Register local publication before yielding, including files beyond L1 admission,
        // so a confirmed card waits for its disk copy instead of re-downloading from Blossom.
        // Offload the multi-MB ARGB thumbnail decode off this main-thread
        // reconcile. The main-confined thumbnail-cache put resumes on Main
        // via launchMutation's Main.immediate scope. Mirrors the
        // receive/render path in WhiteNoiseApp.
        val posterSource = attachment
        appState.launchMutation {
            val decoded = decodeMediaThumbnailOffMain(posterSource)
            if (decoded != null && mediaUploadSessionStillCurrent(account)) {
                appState.cacheMediaThumbnail(cacheKey, decoded)
            }
        }
    }
    if (retained.acceptedPending) {
        retainedMediaUploads.remove(optimisticKey)
        activeUploadKeys.remove(optimisticKey)
    }
}
