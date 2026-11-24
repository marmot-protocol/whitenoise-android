package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceSendStage
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import dev.ipf.whitenoise.android.ui.conversation.media.uploadSourcesDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Token-aware admission may safely repeat a closed-runtime call because MDK
 * deduplicates the original client token. The injected legacy publisher
 * still retries only failures proven to occur before relay publication.
 */
private fun ConversationController.isRetryableTextAdmissionError(throwable: Throwable): Boolean =
    isTransientRelaySendError(throwable) ||
        (textPublisher == null && isTransientRuntimeWorkerError(throwable))

/**
 * The lock an emoji send takes around its publish only, so uploads never hold up cancel or other
 * mutations. The acceptance bookkeeping happens inside the same lock, so a cancel waiting for it
 * sees the send as owned by MDK (or uncertain) and cannot report success for a published send. A
 * rejection that published nothing, such as a stale epoch, leaves the send pre-acceptance.
 */
private fun ConversationController.commitPublishLock(
    account: String,
    optimisticKey: String,
    controllerContext: CoroutineContext,
    uploadJob: Job? = null,
): EmojiPublishLock =
    { publish ->
        appState.withGroupCommitLock(account, group.groupIdHex) {
            // Upload cancellation ends before publication can acquire native ownership.
            withContext(controllerContext) {
                appState.untrackInFlightMediaUpload(account, group.groupIdHex, optimisticKey, uploadJob)
            }
            try {
                publish().also { summary ->
                    withContext(controllerContext) { recordOptimisticSendAcceptance(optimisticKey, summary) }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Throwable,
            ) {
                withContext(controllerContext) { recordSendAdmissionFailure(optimisticKey, failure) }
                throw failure
            }
        }
    }

/**
 * Uploads a send with file-backed items through MDK's file path, with the same commit lock,
 * cancellation check, retry capture and draft/admission rules as the in-memory path. While the
 * transfer holds the lock, the user's Cancel reaches it through [RetainedMediaUpload.requestTransferCancel].
 * A send of stored draft files uploads from the draft instead, see [uploadDraftStoredRetained].
 */
internal suspend fun ConversationController.uploadFileBackedRetained(
    account: String,
    key: String,
    tempId: String,
    retained: RetainedMediaUpload,
    startedAtMs: Long,
): MediaUploadResultFfi {
    if (retained.isDraftStored) return uploadDraftStoredRetained(account, key, tempId, retained, startedAtMs)
    val batchCiphertextBytes =
        checkNotNull(withContext(Dispatchers.IO) { fileBatchCiphertextBytes() }) {
            "file-backed upload limits unavailable"
        }
    val stagingDirectory = uploadSourcesDirectory(appState.appContext.cacheDir)
    return withFileUploadRequest(
        retained.attachments,
        retained.caption,
        stagingDirectory,
        batchCiphertextBytes,
    ) { request ->
        mediaFileUploader?.invoke(account, group.groupIdHex, request, retained::attachTransferCancel)
            ?: appState
                .withGroupCommitLock(account, group.groupIdHex) {
                    requireOptimisticSendNotCancelled(key)
                    val outcome =
                        withNativeTransferControl(
                            retained::attachTransferCancel,
                            onProgress = retained::reportTransferProgress,
                        ) { control ->
                            appState.marmotIo(MarmotTraceSection.MEDIA_UPLOAD) {
                                uploadOrAdmitComposerMediaFilesWithToken(
                                    account,
                                    group.groupIdHex,
                                    request,
                                    control,
                                    tempId,
                                )
                            }
                        }
                    outcome.acceptance?.let { recordOptimisticSendAcceptance(key, it) }
                    outcome
                }.also { outcome ->
                    outcome.captureForRetry(retained, appState.pendingSendDiagnostics, tempId, startedAtMs)
                }.upload
    }
}

/**
 * Uploads a send of files the composer's draft stores, under the same commit lock, cancellation check, acceptance
 * record, progress and retry capture as a file upload. The first attempt reads the draft's own copies, so nothing is
 * read from the pick again. When it fails, the send copies its files out of the draft so a Retry needs nothing the
 * draft still holds, and later attempts upload those copies. The draft send that follows admits the references.
 */
private suspend fun ConversationController.uploadDraftStoredRetained(
    account: String,
    key: String,
    tempId: String,
    retained: RetainedMediaUpload,
    startedAtMs: Long,
): MediaUploadResultFfi {
    if (retained.ownsDraftCopies) return uploadOwnedDraftCopies(account, key, tempId, retained, startedAtMs)
    val upload = DraftFilesUpload(retained.attachments, retained.caption)
    return appState
        .withGroupCommitLock(account, group.groupIdHex) {
            requireOptimisticSendNotCancelled(key)
            val outcome =
                try {
                    withNativeTransferControl(
                        retained::attachTransferCancel,
                        onProgress = retained::reportTransferProgress,
                    ) { control ->
                        appState.marmotIo(MarmotTraceSection.MEDIA_UPLOAD) {
                            uploadComposerDraftFilesWithToken(account, group.groupIdHex, upload, control, tempId)
                        }
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable,
                ) {
                    // Copied under the lock, before another send of this chat can clear the draft.
                    if (failure !is CancellationException && !retained.isTransferCancelRequested) {
                        keepDraftFiles(account, retained)
                    }
                    throw failure
                }
            // A send MDK already owns is recorded under the lock, as the file-backed path does.
            outcome.acceptance?.let { recordOptimisticSendAcceptance(key, it) }
            outcome
        }.also { outcome ->
            retained.draftUploadRevision = outcome.draftRevision
            outcome.captureForRetry(retained, appState.pendingSendDiagnostics, tempId, startedAtMs)
        }.upload
}

/** Gives a send whose upload from the draft failed private copies of its stored files; a failed copy keeps none. */
private suspend fun ConversationController.keepDraftFiles(
    account: String,
    retained: RetainedMediaUpload,
) {
    val directory = uploadSourcesDirectory(appState.appContext.cacheDir)
    retained.keepDraftCopies(appState::closeStagedUploadSourceOffMain) { copies ->
        appState.marmotIo(MarmotTraceSection.MEDIA_UPLOAD) {
            copyDraftFiles(account, group.groupIdHex, retained.attachments, directory, copies)
        }
    }
}

/**
 * Admits [retained]'s uploaded [references] under the commit lock its caller holds. When MDK refuses a send of stored
 * draft files for a stale epoch, its Retry must upload the files again, so the send copies them out of the draft before
 * the lock is released and another send of this chat can clear the draft.
 */
internal suspend fun ConversationController.admitRetainedMedia(
    account: String,
    references: List<MediaAttachmentReferenceFfi>,
    retained: RetainedMediaUpload,
    token: String,
): SendSummaryFfi =
    try {
        appState.marmotIo(MarmotTraceSection.MEDIA_SEND) {
            publishRetainedMedia(account, group.groupIdHex, references, retained, token)
        }
    } catch (
        @Suppress("TooGenericExceptionCaught") failure: Throwable,
    ) {
        // The same refusal makes the Retry drop the references and upload again, see forgetUploadAfter.
        if (retained.isDraftStored && !retained.ownsDraftCopies && isStaleMediaReferenceEpoch(failure)) {
            keepDraftFiles(account, retained)
        }
        throw failure
    }

/**
 * Uploads the private copies a failed send of stored draft files owns, as a file upload under the same lock, cancel,
 * progress and retry capture. The upload revision it may consume is the one its first attempt captured.
 */
private suspend fun ConversationController.uploadOwnedDraftCopies(
    account: String,
    key: String,
    tempId: String,
    retained: RetainedMediaUpload,
    startedAtMs: Long,
): MediaUploadResultFfi {
    val batchCiphertextBytes =
        checkNotNull(withContext(Dispatchers.IO) { fileBatchCiphertextBytes() }) {
            "file-backed upload limits unavailable"
        }
    val upload = DraftFilesUpload(retained.attachments, retained.caption)
    val stagingDirectory = uploadSourcesDirectory(appState.appContext.cacheDir)
    return withFileUploadRequest(
        retained.ownedUploadAttachments(),
        retained.caption,
        stagingDirectory,
        batchCiphertextBytes,
    ) { request ->
        appState
            .withGroupCommitLock(account, group.groupIdHex) {
                requireOptimisticSendNotCancelled(key)
                val outcome =
                    withNativeTransferControl(
                        retained::attachTransferCancel,
                        onProgress = retained::reportTransferProgress,
                    ) { control ->
                        appState.marmotIo(MarmotTraceSection.MEDIA_UPLOAD) {
                            uploadOwnedDraftCopiesWithToken(account, group.groupIdHex, upload, request, control, tempId)
                        }
                    }
                outcome.acceptance?.let { recordOptimisticSendAcceptance(key, it) }
                outcome
            }.also { outcome ->
                outcome.captureForRetry(retained, appState.pendingSendDiagnostics, tempId, startedAtMs)
            }.upload
    }
}

/** Retires host cancellation when a failed admission may already have published. */
private fun ConversationController.recordSendAdmissionFailure(
    optimisticKey: String,
    failure: Throwable,
) {
    if (failure is OptimisticSendCancelledException || !isAmbiguousRelayDeliveryError(failure)) {
        return
    }
    if (optimisticSendPhases[optimisticKey] == OptimisticSendPhase.PRE_ACCEPTANCE) {
        optimisticSendPhases[optimisticKey] = OptimisticSendPhase.ACCEPTANCE_UNKNOWN
    }
}

/** Runs [block] under the group commit lock, or directly when [skip] says the block locks its own publish. */
private suspend fun <T> ConversationController.commitLockUnless(
    skip: Boolean,
    account: String,
    block: suspend () -> T,
): T = if (skip) block() else appState.withGroupCommitLock(account, group.groupIdHex, block)

/**
 * The artwork a composer message or reply carries before publication.
 * A lookup failure is raised as an [EmojiUploadFailure], a definite pre-publication failure.
 */
internal suspend fun ConversationController.emojiArtworkForSend(
    account: String,
    text: String,
): List<LocalEmojiArtwork> {
    val applies = emojiSendApplies(textPublisher != null)
    if (!applies) return emptyList()
    // The lookup runs before anything is published, so its failure is never an uncertain delivery.
    return runCatchingCancellable {
        customEmojiReader?.invoke(text) ?: emojiArtworkIncludingConversation(account, text)
    }.getOrElse { failure -> throw failure as? EmojiSendLimitException ?: EmojiUploadFailure(failure) }
}

/**
 * Admission failures retry for plain text. An emoji send retries only an upload-stage failure,
 * because nothing was published then, and never a failure of the publish itself. Null artwork
 * means its lookup failed, which also published nothing.
 */
private fun ConversationController.isRetryableComposerSend(
    emojiArtwork: List<LocalEmojiArtwork>?,
    throwable: Throwable,
): Boolean =
    if (emojiArtwork?.isEmpty() == true) {
        isRetryableTextAdmissionError(throwable)
    } else {
        isRetryableEmojiUploadFailure(throwable)
    }

/**
 * One native publication of the composer text: the durable token-bound send, or, when the text
 * uses the user's own emoji, upload-only images followed by one tagged media send. The optimistic
 * send is re-checked on this controller's context after the upload and again inside the commit
 * lock right before publishing, so a cancelled send is never published.
 */
@Suppress("LongParameterList")
private suspend fun ConversationController.publishComposerTextOnce(
    account: String,
    replyTarget: String?,
    text: String,
    clientToken: String,
    probeExistingAdmission: Boolean,
    emojiArtwork: List<LocalEmojiArtwork>,
    optimisticKey: String,
): dev.ipf.marmotkit.SendSummaryFfi {
    // The native calls run on the IO dispatcher, but the send phases belong to this controller's context.
    val controllerContext = currentCoroutineContext()
    val ensureCurrent: suspend () -> Unit = {
        withContext(controllerContext) {
            requireOptimisticSendNotCancelled(optimisticKey)
            if (appState.activeAccountRef != account ||
                appState.runtimeGeneration != conversationRuntimeGeneration ||
                appState.mediaUploadSessionEpoch() != mediaUploadSessionEpoch
            ) {
                throw CancellationException("composer artwork owner changed")
            }
        }
    }
    val injected = customEmojiSender
    if (emojiArtwork.isNotEmpty()) ensureCurrent()
    val uploadJob =
        if (emojiArtwork.isEmpty()) {
            null
        } else {
            appState.trackInFlightMediaUpload(account, group.groupIdHex, optimisticKey)
        }
    try {
        return when {
            emojiArtwork.isEmpty() ->
                publishDurableComposerText(account, replyTarget, text, clientToken, probeExistingAdmission)
            injected != null -> {
                val lock = commitPublishLock(account, optimisticKey, controllerContext, uploadJob)
                injected(account, text, emojiArtwork, ensureCurrent, lock)
            }
            else ->
                appState.marmotIo(MarmotTraceSection.TEXT_SEND) {
                    sendTextWithCustomEmoji(
                        account,
                        group.groupIdHex,
                        text,
                        emojiArtwork,
                        appState.emojiUploads,
                        ensureCurrent = ensureCurrent,
                        publishLock = commitPublishLock(account, optimisticKey, controllerContext, uploadJob),
                        replyTarget = replyTarget,
                    )
                }
        }
    } finally {
        appState.untrackInFlightMediaUpload(account, group.groupIdHex, optimisticKey, uploadJob)
    }
}

/**
 * Admits one logical text/reply token to native durable ownership. Proven
 * pre-admission connectivity failures reuse the token; interrupted admission
 * queries native status before another call. Acceptance stays pending until
 * the authoritative projection reports publication. Native convergence owns
 * accepted delivery; the host never invents another semantic send to retry it.
 */
@Suppress("LongMethod") // Retry admission, tracing, and lock ownership form one atomic policy boundary.
internal suspend fun ConversationController.publishTextWithRetry(
    replyTarget: String?,
    account: String,
    trimmed: String,
    trace: PerformanceTrace?,
    clientToken: String,
    optimisticKey: String,
): dev.ipf.marmotkit.SendSummaryFfi =
    appState.withConversationTextSendOrder(account, group.groupIdHex) {
        // Artwork uploads run outside the commit lock, and only an upload-stage failure is retried.
        // Resolving its artwork inside the text order keeps a later message from publishing first,
        // and inside the attempt lets a transient lookup failure retry before anything is published.
        val artwork = SendEmojiArtwork { emojiArtworkForSend(account, trimmed) }
        retryPendingConversationSend(
            connectivityRecoveryGeneration = appState.validatedConnectivityRecoveryGeneration,
            cancellationGeneration = optimisticCancellationGeneration,
            retryableFailure = { isRetryableComposerSend(artwork.value, it) },
            onTransientFailure = { attempt, _ -> logSendRetry(trace, clientToken, attempt) },
        ) { attempt ->
            // A cancel wakes the retry wait, so check it before a lookup that may fail again and retry forever.
            requireOptimisticSendNotCancelled(optimisticKey)
            val emojiArtwork = untilSendCancelled(optimisticKey) { artwork.resolve() }
            // Serialize only this commit-producing FFI attempt. Releasing the
            // commit lock before retry backoff keeps reactions and other
            // mutations usable; the outer text-order lock keeps later text
            // sends behind this one until its outcome is known.
            val lockWaitStartMs = trace?.let { traceNowMs() }
            updatePendingSendStage(clientToken, PerformanceSendStage.WAITING_COMMIT_LOCK, attempt)
            // An emoji send uploads outside the commit lock and locks only its publish, so a cancel
            // taking the same lock never waits for a slow upload.
            commitLockUnless(emojiArtwork.isNotEmpty() && textPublisher == null, account) {
                requireOptimisticSendNotCancelled(optimisticKey)
                val lockHeldAtMs = trace?.let { traceNowMs() }
                sendTrace(
                    trace,
                    PerformancePhase.COMMIT_LOCK_ACQUIRED,
                    durationMs = lockHeldAtMs?.minus(lockWaitStartMs ?: lockHeldAtMs) ?: 0L,
                    attempt = attempt,
                )
                updatePendingSendStage(clientToken, PerformanceSendStage.FFI_ADMISSION, attempt)
                // Measure admission separately from transport publication.
                val ffiStartMs = trace?.let { traceNowMs() }
                sendTrace(
                    trace,
                    PerformancePhase.FFI_START,
                    result = PerformanceResult.PENDING,
                    layer = PerformanceLayer.FFI,
                    attempt = attempt,
                )
                try {
                    val summary =
                        textPublisher?.invoke(replyTarget, account, group.groupIdHex, trimmed)
                            ?: publishComposerTextOnce(
                                account,
                                replyTarget,
                                trimmed,
                                clientToken,
                                attempt > 1,
                                emojiArtwork,
                                optimisticKey,
                            )
                    recordOptimisticSendAcceptance(optimisticKey, summary)
                    sendTrace(
                        trace,
                        PerformancePhase.FFI_RETURN,
                        durationMs = ffiStartMs?.let { traceNowMs() - it } ?: 0L,
                        layer = PerformanceLayer.FFI,
                        attempt = attempt,
                        count = summary.messageIds.size,
                    )
                    if (summary.acceptDisposition == SendAcceptDispositionFfi.PUBLISHED) {
                        sendTrace(
                            trace,
                            PerformancePhase.TRANSPORT_COMPLETE,
                            layer = PerformanceLayer.TRANSPORT,
                            count = summary.messageIds.size,
                        )
                    }
                    summary
                } catch (throwable: Throwable) {
                    // Presentation may still be parsing when this child
                    // returns. Retire host cancellation before releasing
                    // the commit lock if publication may have happened.
                    recordSendAdmissionFailure(optimisticKey, throwable)
                    sendTrace(
                        trace,
                        PerformancePhase.FFI_ERROR,
                        durationMs = ffiStartMs?.let { traceNowMs() - it } ?: 0L,
                        result = PerformanceResult.FAILURE,
                        layer = PerformanceLayer.FFI,
                        attempt = attempt,
                    )
                    throw throwable
                }
            }
        }
    }

/**
 * Trace a transient send retry with the current relay-health snapshot.
 * Aggregate connection-state counts only (no relay URLs, account/group/
 * message ids, or payload) so the #294 failure window is observable without
 * violating the repo's privacy posture. Best-effort: a failure to read
 * health must never escalate a send retry into an error.
 */
private suspend fun ConversationController.logSendRetry(
    trace: PerformanceTrace?,
    optimisticId: String,
    attempt: Int,
) {
    if (trace == null) return
    val health = runCatchingCancellable { appState.marmotIo { relayHealth() } }.getOrNull()
    sendTrace(
        trace,
        PerformancePhase.TRANSIENT_RETRY,
        result = PerformanceResult.FAILURE,
        layer = PerformanceLayer.TRANSPORT,
        attempt = attempt,
        connectedRelays = health?.connected?.toInt(),
        totalRelays = health?.totalRelays?.toInt(),
    )
    updatePendingSendStage(optimisticId, PerformanceSendStage.WAITING_COMMIT_LOCK, attempt)
}

/** Updates the closed stage sampled if this send crosses a pending checkpoint. */
private fun ConversationController.updatePendingSendStage(
    optimisticId: String,
    stage: PerformanceSendStage,
    attempt: Int,
) {
    appState.pendingSendDiagnostics.update(optimisticId, stage, attempt)
}

/** Settle a manual text retry without republishing an uncertain delivery. */
internal fun ConversationController.handleFailedSendRetryFailure(
    throwable: Throwable,
    key: String,
    tempId: String,
    current: TimelineMessage,
    refreshedRecord: AppMessageRecordFfi,
    order: ULong,
    account: String,
    retryTrace: PerformanceTrace?,
) {
    if (throwable is OptimisticSendCancelledException) {
        discardCancelledSendAttempt(key, tempId)
        return
    }
    retireCancelledSendTombstone(throwable, key)
    throwable.rethrowIfCancellation()
    if (BuildConfig.DEBUG) Log.w("DMConversation", "retryFailedSend failed", throwable)
    when {
        discardedDuringRetry.remove(key) -> {
            // User discarded mid-flight; don't restore the Failed bubble.
            discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_IN_FLIGHT)
            trimCancelledSendTombstones()
        }
        throwable.isUseAfterEviction() -> {
            discardOptimisticSend(key, tempId, OptimisticSendDiscard.ABANDONED)
            markActiveAccountRemovedFromMembers(account)
        }
        isAmbiguousRelayDeliveryError(throwable) -> {
            // Publication may already have reached a relay. Keep the
            // existing row Pending; another high-level send could mint a
            // duplicate event.
            markTextDeliveryUncertain(key, tempId, retryTrace)
            publishTimelineFromIndexes()
        }
        optimisticSendIsMdkOwnedOrUnknown(key) -> {
            publishTimelineFromIndexes()
        }
        else -> {
            optimisticSendPhases[key] = OptimisticSendPhase.FAILED
            optimisticMessages[key] =
                current.copy(
                    record = refreshedRecord,
                    status = MessageStatus.Failed,
                    timelineOrder = order,
                )
            failOptimisticChatListPreview(tempId)
            suppressUnpublishedCopiesOf(refreshedRecord)
            publishTimelineFromIndexes()
            appState.pendingSendDiagnostics.forget(tempId)
            presentSendFailure(appState, throwable, sendFailureAttempt(key))
        }
    }
}

/** Retry and Discard act on a failed original before its dependent durable edit. */
internal fun ConversationController.failedEditForAction(item: TimelineMessage): OptimisticEdit? =
    optimisticEdits[item.record.messageIdHex]?.takeIf {
        it.status == MessageStatus.Failed &&
            !(it.durableIntent != null && optimisticMessages[item.id]?.status == MessageStatus.Failed)
    }

/** An unavailable acknowledgement cannot authorize discarding a durably queued revision. */
internal suspend fun ConversationController.discardPendingEdit(
    target: String,
    intent: DurablePendingEditIntent,
) {
    val account = conversationAccountRef ?: return
    val result =
        runCatchingCancellable {
            pendingEditStatusReader(account, group.groupIdHex, intent.editClientToken)
        }
    val current =
        optimisticEdits[target]?.takeIf {
            it.durableIntent == intent && it.status == MessageStatus.Failed
        } ?: return
    val status = result.getOrNull()
    when {
        result.isFailure -> {
            if (appState.activeAccountRef == account) {
                appState.presentFailure(
                    R.string.toast_couldnt_edit_message,
                    "MESSAGE_EDIT",
                    requireNotNull(result.exceptionOrNull()),
                )
            }
        }
        status == null || status == LocalSendStatusFfi.Rejected -> optimisticEdits.remove(target)
        else -> optimisticEdits[target] = current.withNativeEditStatus(status)
    }
    publishTimelineFromIndexes()
}
