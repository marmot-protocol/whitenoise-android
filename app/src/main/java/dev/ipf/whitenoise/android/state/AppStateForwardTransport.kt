package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import dev.ipf.whitenoise.android.media.readWithin
import dev.ipf.whitenoise.android.ui.conversation.media.StagedDiskClaims
import dev.ipf.whitenoise.android.ui.conversation.media.fileUploadCopyBytes
import dev.ipf.whitenoise.android.ui.conversation.media.fileUploadFits
import dev.ipf.whitenoise.android.ui.conversation.media.fileUploadSnapshotBytes
import dev.ipf.whitenoise.android.ui.conversation.media.nativeFileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.uploadSourcesDirectory
import dev.ipf.whitenoise.android.ui.conversation.media.usableSpaceFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Builds the production [ForwardTransport] for one forwarding operation with
 * explicit account ownership. Source attachment lookup and plaintext
 * materialization run only under [sourceAccount]; destination upload, commit
 * locking, token-bound publish with its engine handoff, and retries run only under
 * [account]. Neither boundary ever re-reads the live active account: each
 * revalidates that its own bound owner is still a signed-in signing account,
 * so removal or sign-out of either owner stops the operation without falling
 * back to another account. [diagnostics] times each phase while a local
 * diagnostics session is active and receives no identifiers or content.
 */
@Suppress("LongMethod")
internal fun WhiteNoiseAppState.forwardTransport(
    sourceAccount: String,
    account: String,
    diagnostics: ForwardDiagnostics? = null,
): ForwardTransport {
    /** Rejects source reads after their explicitly bound signing account leaves the runtime. */
    fun requireSourceAccount() {
        if (!isForwardOwnerSignedIn(sourceAccount)) throw ForwardSessionInvalidatedException()
    }

    /** Fails the session when the destination owner is no longer signed in. */
    fun requireDestinationAccount() {
        if (!isForwardOwnerSignedIn(account)) throw ForwardSessionInvalidatedException()
    }

    // Disk this forward's concurrent destination uploads hold for staged copies, so they never count on the same space.
    val diskClaims = StagedDiskClaims()

    // Native calls a publish deadline may stop waiting for. A call is abandoned, not cancelled: when the wait ends it
    // keeps running, and may hold one marmotIo thread until the native call returns. Status reads and admission run
    // one at a time, so at most one such call is outstanding per message per attempt. Its result is never observed
    // and writes nothing back. An abandoned admission may still land after the user dismissed the forward, which
    // matches MDK's outlives-cancellation contract; its client token keeps it from becoming a second message on a
    // retry. The blocking call itself runs on marmotIo's dispatcher; this scope only owns the detached wait, so it
    // starts undispatched.
    val nativeCalls = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    return object : ForwardTransport {
        /** Resolves and decrypts one attachment strictly through the source account. */
        override suspend fun materialize(
            sourceGroupIdHex: String,
            sourceMessageIdHex: String,
            source: dev.ipf.whitenoise.android.core.ForwardAttachmentSource,
        ): PendingAttachment {
            requireSourceAccount()
            val request =
                AttachmentTransferRequest(
                    accountRef = sourceAccount,
                    groupIdHex = sourceGroupIdHex,
                    messageIdHex = sourceMessageIdHex,
                    attachmentIndex = source.attachmentIndex,
                )
            return diagnostics.span({}, ForwardDiagnostics::sourceReady) {
                materializeForwardAttachment(
                    source = source,
                    resolveAuthoritativeReference = {
                        requireSourceAccount()
                        diagnostics.span({}, ForwardDiagnostics::sourceReferenceResolved) {
                            resolveAttachmentReference(request).also { requireSourceAccount() }
                        }
                    },
                    downloadPlaintext = { reference ->
                        requireSourceAccount()
                        materializeAttachmentPlaintextIsolated(request, reference, diagnostics)
                            .also { requireSourceAccount() }
                    },
                )
            }
        }

        /**
         * Uploads already-materialized bytes strictly through the destination account. The upload goes through
         * MDK's file-backed path, which bounds every transfer attempt and stops when the user cancels, and a
         * transfer that never starts fails at the upload stage instead of looking stalled for minutes (#2556).
         * The in-memory upload stays for runtimes without the native library, as in JVM tests, and for messages
         * the file path cannot take: more items or bytes than one native file batch, or too little free disk for
         * the staged copies once the other destinations of this forward have claimed theirs.
         */
        override suspend fun upload(
            targetGroupIdHex: String,
            message: PreparedForwardMessage.Media,
        ): List<MediaAttachmentReferenceFfi> {
            requireDestinationAccount()
            val copyBytes = fileUploadCopyBytes(message.attachments)
            val snapshotBytes = fileUploadSnapshotBytes(message.attachments)
            val batchAndUsableBytes =
                withContext(Dispatchers.IO) {
                    nativeFileBackedSendLimits()?.let { limits ->
                        limits.batchCiphertextBytes to usableSpaceFor(uploadSourcesDirectory(appContext.cacheDir))
                    }
                }
            // Claimed here, not inside withContext, which drops its result when the caller is cancelled on the way
            // back: no suspension point may sit between the claim and the try that releases it.
            val claim =
                batchAndUsableBytes?.let { (batch, usableBytes) ->
                    diskClaims.claimIf(copyBytes, usableBytes) { available ->
                        fileUploadFits(message.attachments, batch, available)
                    }
                }
            val batchCiphertextBytes = batchAndUsableBytes?.first?.takeIf { claim != null }
            val uploaded =
                try {
                    diagnostics.span(ForwardDiagnostics::uploadStart, ForwardDiagnostics::uploadReturn) {
                        if (batchCiphertextBytes == null) {
                            uploadInMemory(targetGroupIdHex, message)
                        } else {
                            // The staged snapshots now sit on disk, where free space counts them.
                            uploadFileBacked(targetGroupIdHex, message, batchCiphertextBytes) {
                                claim?.shrinkBy(snapshotBytes)
                            }
                        }
                    }
                } finally {
                    claim?.release()
                }
            requireDestinationAccount()
            return uploaded
        }

        /**
         * Stages the bytes as private snapshots for one native file upload that must start within a bound, calling
         * [onStaged] once the snapshots are written and before MDK starts. Abandoning
         * a call that never started is safe even while MDK still has it queued. The queued command holds its own
         * reference to the transfer control, so closing this handle does not free it. It checks the cancel before it
         * opens any source and on every chunk, so it never reads the deleted snapshots. With `send = false`, it
         * publishes nothing.
         */
        private suspend fun uploadFileBacked(
            targetGroupIdHex: String,
            message: PreparedForwardMessage.Media,
            batchCiphertextBytes: Long,
            onStaged: () -> Unit,
        ): List<MediaAttachmentReferenceFfi> =
            withFileUploadRequest(
                message.attachments,
                message.caption,
                uploadSourcesDirectory(appContext.cacheDir),
                batchCiphertextBytes,
            ) { request ->
                onStaged()
                withNativeTransferControl(register = {}) { control ->
                    withUploadStartWatchdog(
                        startMillis = FORWARD_UPLOAD_START_MILLIS,
                        progress = { control.processedBytes().toLong() },
                        cancel = control::cancel,
                    ) {
                        marmotIo { uploadMediaFiles(account, targetGroupIdHex, request, control) }
                            .attachments
                            .map { it.reference }
                    }
                }
            }

        /** Passes the bytes across the binding in memory, for runtimes without the native file path. */
        private suspend fun uploadInMemory(
            targetGroupIdHex: String,
            message: PreparedForwardMessage.Media,
        ): List<MediaAttachmentReferenceFfi> =
            marmotIo {
                uploadMedia(
                    account,
                    targetGroupIdHex,
                    MediaUploadRequestFfi(
                        attachments =
                            message.attachments.map { attachment ->
                                MediaUploadAttachmentRequestFfi(
                                    fileName = attachment.fileName,
                                    mediaType = attachment.mediaType,
                                    plaintext = attachment.plaintextBytes,
                                    dim = attachment.dim,
                                    thumbhash = attachment.thumbhash,
                                )
                            },
                        caption = message.caption,
                        send = false,
                        blossomServer = null,
                    ),
                ).attachments.map { it.reference }
            }

        /**
         * Publishes each message under its stable client token while holding the destination commit lock, and counts
         * it as sent only once MDK's engine owns it. A retry re-checks the token first, so an earlier admission is
         * waited on rather than repeated, and the whole of each message is bounded by
         * [FORWARD_PUBLISH_ADMISSION_MILLIS] (#2556).
         */
        override suspend fun publishBatch(
            targetGroupIdHex: String,
            messages: List<PreparedForwardMessage>,
            uploadedReferences: Map<Int, List<MediaAttachmentReferenceFfi>>,
            startIndex: Int,
            clientTokenFor: (messageIndex: Int) -> String,
            onBeforeMessagePublished: (messageIndex: Int) -> Unit,
            onMessagePublished: (messageIndex: Int) -> Unit,
        ) {
            requireDestinationAccount()
            val lockRequestedAtMs = diagnostics?.startSpan()
            withGroupCommitLock(account, targetGroupIdHex) {
                if (lockRequestedAtMs != null) diagnostics?.commitLockAcquired(lockRequestedAtMs)
                for (messageIndex in startIndex until messages.size) {
                    currentCoroutineContext().ensureActive()
                    onBeforeMessagePublished(messageIndex)
                    requireDestinationAccount()
                    val message = messages[messageIndex]
                    val references = uploadedReferences[messageIndex].orEmpty()
                    if (message is PreparedForwardMessage.Media) {
                        check(references.isNotEmpty()) { "missing destination media references" }
                    }
                    publishOne(targetGroupIdHex, messageIndex, message, references, clientTokenFor(messageIndex))
                    requireDestinationAccount()
                    onMessagePublished(messageIndex)
                }
            }
        }

        /**
         * Admits one message and waits for the engine to own it. Every status read and the admission run detached, so
         * the deadline can stop waiting while a blocking native call is still running. The publish span covers
         * admission and engine handoff, with admission timed on its own. A healthy forward never asks for
         * convergence, matching the attachment fixture's contract; stalled and rejected publishes are resolved from
         * the token's status instead.
         */
        private suspend fun publishOne(
            targetGroupIdHex: String,
            messageIndex: Int,
            message: PreparedForwardMessage,
            references: List<MediaAttachmentReferenceFfi>,
            token: String,
        ) {
            val admissionStartedAtMs = diagnostics?.startSpan()
            diagnostics.span(ForwardDiagnostics::publishStart, ForwardDiagnostics::publishReturn) {
                admitForwardPublish(
                    messageIndex = messageIndex,
                    token = token,
                    probe = { detachedNative { localSendStatus(account, targetGroupIdHex, token) } },
                    beforeAdmit = ::requireDestinationAccount,
                    admit = { detachedNative { admit(targetGroupIdHex, message, references, token) } },
                    onAdmitted = {
                        if (admissionStartedAtMs != null) diagnostics?.admissionAccepted(admissionStartedAtMs)
                    },
                )
            }
        }

        /** Admits [message] to [targetGroupIdHex] under [token] through MDK's matching token-bound send. */
        private suspend fun MarmotInterface.admit(
            targetGroupIdHex: String,
            message: PreparedForwardMessage,
            references: List<MediaAttachmentReferenceFfi>,
            token: String,
        ): LocalSendAcceptanceFfi =
            when (message) {
                is PreparedForwardMessage.Text ->
                    sendTextWithClientToken(account, targetGroupIdHex, message.text, token)
                is PreparedForwardMessage.Media ->
                    sendPreparedMediaWithClientToken(
                        account,
                        targetGroupIdHex,
                        references,
                        message.caption,
                        token,
                        null,
                    )
            }

        /** Runs [block] on the native dispatcher outside this coroutine, so cancelling the wait never blocks on it. */
        private suspend fun <T> detachedNative(block: suspend MarmotInterface.() -> T): T =
            nativeCalls
                .async { marmotIo(block) }
                .await()
    }
}

/**
 * Materializes one attachment's plaintext for forwarding without joining the
 * shared memoized download pool and without writing any cache. An unrelated
 * active-account switch cancels and clears that shared pool for UI hygiene;
 * a forwarding operation binds its accounts explicitly and must survive such
 * invalidation, so it reads the existing local layers opportunistically and
 * otherwise downloads within its own session scope. The local layers are the
 * Android memory and disk caches and then MarmotKit's own retained copy of the
 * attachment, the canonical local store that holds every received attachment
 * the user has already opened and every own send, so an attachment the device
 * already holds never crosses the network again. The forwarding session
 * retains and later zeroes its own private copy of the bytes, so skipping
 * cache writes leaks nothing and keeps switch-time cache policy intact.
 * [diagnostics] records the local probe and the native download span only, and
 * [readRetained] is the retained read, injectable so JVM tests can stand in for
 * the native asset.
 */
internal suspend fun WhiteNoiseAppState.materializeAttachmentPlaintextIsolated(
    request: AttachmentTransferRequest,
    reference: MediaAttachmentReferenceFfi,
    diagnostics: ForwardDiagnostics? = null,
    readRetained: suspend (AttachmentTransferRequest) -> ByteArray? = { retainedForwardPlaintext(it) },
): ByteArray {
    val cacheKey =
        mediaCacheKey(
            request.accountRef,
            request.groupIdHex,
            request.messageIdHex,
            request.attachmentIndex,
        )
    val lookupStartedAtMs = diagnostics?.startSpan()
    val cached =
        withContext(Dispatchers.Main.immediate) { cachedMediaPlaintext(cacheKey) }
            ?: withContext(Dispatchers.IO) { diskMediaCache.get(cacheKey) }
    // A retained read that fails only means the network path is used, as before. A source over the forward's own cap
    // is the exception: downloading it cannot succeed, so the typed rejection ends the forward without a download.
    val retained =
        if (cached == null) {
            runCatchingCancellable { readRetained(request) }
                .onFailure { failure -> if (failure is ForwardPayloadTooLargeException) throw failure }
                .getOrNull()
        } else {
            null
        }
    if (lookupStartedAtMs != null) {
        diagnostics?.sourceLookup(
            hit = cached != null || retained != null,
            startedAtMs = lookupStartedAtMs,
            native = retained != null,
        )
    }
    return cached
        ?: retained
        ?: diagnostics
            .span(ForwardDiagnostics::sourceDownloadStart, ForwardDiagnostics::sourceDownloadReturn) {
                marmotIo { downloadMedia(request.accountRef, request.groupIdHex, reference) }
            }.plaintext
}

/**
 * Copies MarmotKit's verified retained plaintext for one attachment into a private buffer, or null when absent. The
 * open carries the forward's cap, so an attachment declaring more is refused without being copied into a lease.
 */
private suspend fun WhiteNoiseAppState.retainedForwardPlaintext(request: AttachmentTransferRequest): ByteArray? {
    val maxBytes = ConversationController.MEDIA_RETAINED_MAX_BYTES
    return readRetainedForwardPlaintext(maxBytes) { openNativeAttachment(request, maxBytes) }
}

/**
 * Copies an opened retained lease into a private buffer of at most [maxBytes] on the IO dispatcher and closes it on
 * every path.
 *
 * A forward session runs on the main dispatcher, and opening a lease returns to that context, so the read runs on IO.
 * The read is bounded by the forward's own retained-bytes cap, so a larger attachment is rejected before it is
 * allocated as [ForwardPayloadTooLargeException], the same failure the session raises for an oversized source, and a
 * forward cancelled mid-copy stops at the next chunk.
 */
internal suspend fun readRetainedForwardPlaintext(
    maxBytes: Long = ConversationController.MEDIA_RETAINED_MAX_BYTES,
    open: suspend () -> AttachmentPlaintext?,
): ByteArray? =
    try {
        open()?.use { lease -> lease.readWithin(maxBytes) }
    } catch (_: AttachmentTooLargeToPresentException) {
        throw ForwardPayloadTooLargeException()
    }
