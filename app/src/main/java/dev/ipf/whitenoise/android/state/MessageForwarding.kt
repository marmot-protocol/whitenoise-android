package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Immutable
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.core.ForwardAttachmentSource
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.MessageProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ForwardOperationPhase {
    Preparing,
    Running,
    Completed,
    PartialFailure,
    Failed,
    Cancelling,
    Cancelled,
}

internal enum class ForwardTargetPhase {
    Waiting,
    Uploading,
    Sending,
    Completed,
    Failed,
    Cancelled,
}

internal enum class ForwardFailureStage {
    Materialize,
    PreparationTimeout,
    Upload,

    /** The destination upload never started; Retry is the user's choice, never automatic (#2556). */
    UploadTimeout,
    Publish,

    /**
     * A message was not owned by MDK's engine in time. It may still be sent; Retry re-checks the same client token,
     * so it is never sent twice, but like the other timeouts it is the user's choice, never automatic (#2556).
     */
    PublishTimeout,

    /**
     * MDK rejected a message at admission or while it was queued. Retry admits it under a fresh token, only when the
     * user asks, so automatic retries never stack rejected messages in the destination.
     */
    PublishRejected,
    PayloadTooLarge,
    Expired,
    SessionChanged,
}

@Immutable
internal data class ForwardTargetProgress(
    val groupIdHex: String,
    val phase: ForwardTargetPhase = ForwardTargetPhase.Waiting,
    val uploadedAttachments: Int = 0,
    val totalAttachments: Int,
    val sentMessages: Int = 0,
    val totalMessages: Int,
    val failureStage: ForwardFailureStage? = null,
    /** This destination has stayed in its current phase past [FORWARD_PHASE_SLOW_MILLIS]; display only. */
    val slow: Boolean = false,
)

@Immutable
internal data class ForwardOperationSnapshot(
    val phase: ForwardOperationPhase,
    val preparedAttachments: Int,
    val totalAttachments: Int,
    val targets: List<ForwardTargetProgress>,
    /** Preparing the source attachments has run past [FORWARD_PHASE_SLOW_MILLIS]; display only. */
    val preparationSlow: Boolean = false,
) {
    val completedTargets: Int get() = targets.count { it.phase == ForwardTargetPhase.Completed }
    val failedTargets: Int get() = targets.count { it.phase == ForwardTargetPhase.Failed }
    val canRetry: Boolean
        get() =
            (phase == ForwardOperationPhase.Failed || phase == ForwardOperationPhase.PartialFailure) &&
                targets.any { target ->
                    target.phase == ForwardTargetPhase.Failed &&
                        target.failureStage != ForwardFailureStage.PayloadTooLarge &&
                        target.failureStage != ForwardFailureStage.Expired &&
                        target.failureStage != ForwardFailureStage.SessionChanged
                }

    /**
     * Timeout is user-recoverable but must not immediately re-enter the same stalled work, so a forward that already
     * waited out a preparation or upload bound fails with Retry instead of going back to in progress.
     */
    val canAutomaticallyRetry: Boolean
        get() =
            canRetry &&
                targets.none { target ->
                    target.failureStage == ForwardFailureStage.PreparationTimeout ||
                        target.failureStage == ForwardFailureStage.UploadTimeout ||
                        target.failureStage == ForwardFailureStage.PublishTimeout ||
                        target.failureStage == ForwardFailureStage.PublishRejected
                }
    val canCancel: Boolean
        get() =
            (phase == ForwardOperationPhase.Preparing || phase == ForwardOperationPhase.Running) &&
                targets.none { target ->
                    target.phase == ForwardTargetPhase.Sending || target.sentMessages > 0
                }
    val isActive: Boolean
        get() =
            phase == ForwardOperationPhase.Preparing ||
                phase == ForwardOperationPhase.Running ||
                phase == ForwardOperationPhase.Cancelling
}

/** Preserves completed destinations while cancelling all unfinished destination work. */
private fun ForwardOperationSnapshot.asCancelled(): ForwardOperationSnapshot =
    copy(
        phase = ForwardOperationPhase.Cancelled,
        preparationSlow = false,
        targets =
            targets.map { target ->
                if (
                    target.phase == ForwardTargetPhase.Completed ||
                    target.phase == ForwardTargetPhase.Failed
                ) {
                    target
                } else {
                    target.copy(phase = ForwardTargetPhase.Cancelled, slow = false)
                }
            },
    )

internal sealed interface PreparedForwardMessage {
    data class Text(
        val text: String,
    ) : PreparedForwardMessage

    data class Media(
        val caption: String?,
        val attachments: List<PendingAttachment>,
        val expiresAtSeconds: ULong?,
    ) : PreparedForwardMessage
}

/** Platform boundary kept injectable so retries and cancellation are deterministic in JVM tests. */
internal interface ForwardTransport {
    suspend fun materialize(
        sourceGroupIdHex: String,
        sourceMessageIdHex: String,
        source: ForwardAttachmentSource,
    ): PendingAttachment

    /** Cancels source work that outlived a forwarding deadline or explicit cancellation. */
    fun cancelStalledMaterialization() = Unit

    suspend fun upload(
        targetGroupIdHex: String,
        message: PreparedForwardMessage.Media,
    ): List<MediaAttachmentReferenceFfi>

    /**
     * Admits [messages] from [startIndex] to [targetGroupIdHex], each under the stable token [clientTokenFor] returns
     * for its index, so a retry of the same message to the same destination can never publish it twice.
     */
    @Suppress("LongParameterList")
    suspend fun publishBatch(
        targetGroupIdHex: String,
        messages: List<PreparedForwardMessage>,
        uploadedReferences: Map<Int, List<MediaAttachmentReferenceFfi>>,
        startIndex: Int,
        clientTokenFor: (messageIndex: Int) -> String,
        onBeforeMessagePublished: (messageIndex: Int) -> Unit,
        onMessagePublished: (messageIndex: Int) -> Unit,
    )
}

/** Keeps transport cleanup failures from masking cancellation or the actionable timeout state. */
@Suppress("SwallowedException")
private fun ForwardTransport.cancelStalledMaterializationBestEffort() {
    try {
        cancelStalledMaterialization()
    } catch (_: Exception) {
        // State cleanup must still complete; transport cleanup is best effort.
    }
}

/** Resolves optimistic metadata before any source bytes cross into a forward session. */
internal suspend fun materializeForwardAttachment(
    source: ForwardAttachmentSource,
    resolveAuthoritativeReference: suspend () -> MediaAttachmentReferenceFfi?,
    downloadPlaintext: suspend (MediaAttachmentReferenceFfi) -> ByteArray,
): PendingAttachment {
    val reference =
        source.reference.takeIf { it.sourceEpoch != 0uL }
            ?: resolveAuthoritativeReference()?.takeIf { it.sourceEpoch != 0uL }
            ?: throw AttachmentReferenceNotReadyException()
    val plaintext = downloadPlaintext(reference)
    return PendingAttachment(
        // The download path may return its in-memory cache buffer. Forwarding
        // owns and later clears this copy without corrupting the source cache.
        plaintextBytes = plaintext.copyOf(),
        mediaType = reference.mediaType,
        fileName = reference.fileName,
        dim = reference.dim,
        thumbhash = reference.thumbhash,
    )
}

private const val FORWARD_PAYLOAD_TOO_LARGE_MESSAGE = "forward payload exceeds the retained-byte limit"
private const val DEFAULT_FORWARD_RETRY_DELAY_MS = 1_000L

internal class ForwardPayloadTooLargeException : IllegalArgumentException(FORWARD_PAYLOAD_TOO_LARGE_MESSAGE)

internal class ForwardAttachmentExpiredException : IllegalStateException("forward attachment expired")

internal class ForwardSessionInvalidatedException : IllegalStateException("forward session account changed")

internal class ForwardPreparationTimeoutException : IllegalStateException("forward attachment preparation timed out")

private class ForwardMaterializationAccounting(
    private val retainedPlaintextBuffers: MutableList<ByteArray>,
    private val maxRetainedBytes: Long,
) {
    private val lock = Any()
    private var retainedBytes = 0L
    private var preparedAttachmentCount = 0

    fun retain(attachment: PendingAttachment): Int =
        synchronized(lock) {
            if (attachment.plaintextBytes.isEmpty()) {
                throw IllegalStateException("materialized attachment is empty")
            }
            retainedPlaintextBuffers += attachment.plaintextBytes
            retainedBytes += attachment.plaintextBytes.size
            if (retainedBytes > maxRetainedBytes) throw ForwardPayloadTooLargeException()
            preparedAttachmentCount += 1
            preparedAttachmentCount
        }
}

private suspend fun materializeForwardMediaMessage(
    message: ForwardMessagePayload.Media,
    materializationGate: Semaphore,
    accounting: ForwardMaterializationAccounting,
    transport: ForwardTransport,
    clockSeconds: () -> ULong,
    onAttachmentPrepared: (Int) -> Unit,
): PreparedForwardMessage.Media =
    coroutineScope {
        ensureForwardAttachmentNotExpired(message.expiresAtSeconds, clockSeconds)
        val attachments =
            message.attachments
                .map { source ->
                    async {
                        materializationGate.withPermit {
                            currentCoroutineContext().ensureActive()
                            val attachment =
                                transport.materialize(
                                    sourceGroupIdHex = message.sourceGroupIdHex,
                                    sourceMessageIdHex = message.sourceMessageIdHex,
                                    source = source,
                                )
                            onAttachmentPrepared(accounting.retain(attachment))
                            attachment
                        }
                    }
                }.awaitAll()
        ensureForwardAttachmentNotExpired(message.expiresAtSeconds, clockSeconds)
        PreparedForwardMessage.Media(message.caption, attachments, message.expiresAtSeconds)
    }

/**
 * One retryable fan-out operation. Plaintext is materialized once, while every
 * destination owns a separate uploaded-reference map. A retry resumes at that
 * destination's first unpublished message and can never reuse another chat's
 * references or duplicate an already-successful publish. The injected scope,
 * [runTargets], and its child coroutines must stay on one thread because the
 * per-target maps are intentionally confined rather than synchronized.
 */
internal class ForwardSession(
    private val scope: CoroutineScope,
    private val messages: List<ForwardMessagePayload>,
    targetGroupIds: List<String>,
    private val transport: ForwardTransport,
    private val maxRetainedBytes: Long = ConversationController.MEDIA_RETAINED_MAX_BYTES,
    private val targetFanout: Int = 2,
    private val preparationTimeoutMillis: Long = DEFAULT_FORWARD_PREPARATION_TIMEOUT_MILLIS,
    private val clockSeconds: () -> ULong = {
        (System.currentTimeMillis() / MILLIS_PER_SECOND).toULong()
    },
    private val onFailure: (targetGroupIdHex: String?, stage: ForwardFailureStage, throwable: Throwable) -> Unit =
        { _, _, _ -> },
) {
    private val normalizedTargets = MessageProjector.normalizeForwardTargets(targetGroupIds)
    private val totalAttachments =
        messages.sumOf { message ->
            (message as? ForwardMessagePayload.Media)?.attachments?.size ?: 0
        }
    private val totalMessageCount = messages.size
    private val _state =
        MutableStateFlow(
            ForwardOperationSnapshot(
                phase =
                    if (totalAttachments > 0) {
                        ForwardOperationPhase.Preparing
                    } else {
                        ForwardOperationPhase.Running
                    },
                preparedAttachments = 0,
                totalAttachments = totalAttachments,
                targets =
                    normalizedTargets.map { groupIdHex ->
                        ForwardTargetProgress(
                            groupIdHex = groupIdHex,
                            totalAttachments = totalAttachments,
                            totalMessages = totalMessageCount,
                        )
                    },
            ),
        )
    val state: StateFlow<ForwardOperationSnapshot> = _state.asStateFlow()

    private var preparedMessages: List<PreparedForwardMessage>? = null
    private val retainedPlaintextBuffers = mutableListOf<ByteArray>()
    private val uploadedReferencesByTarget = mutableMapOf<String, MutableMap<Int, List<MediaAttachmentReferenceFfi>>>()
    private val publishedMessageCountByTarget = normalizedTargets.associateWithTo(mutableMapOf()) { 0 }

    private val publishTokens = ForwardPublishTokens()
    private var activeJob: Job? = null
    private var released = false
    private var started = false

    init {
        require(messages.isNotEmpty()) { "forward messages must not be empty" }
        require(normalizedTargets.isNotEmpty()) { "forward targets must not be empty" }
        require(targetFanout > 0) { "target fan-out must be positive" }
        require(preparationTimeoutMillis > 0) { "preparation timeout must be positive" }
        require(
            messages.all { message ->
                when (message) {
                    is ForwardMessagePayload.Text -> message.text.isNotBlank()
                    is ForwardMessagePayload.Media ->
                        message.sourceGroupIdHex.isNotBlank() &&
                            message.sourceMessageIdHex.isNotBlank() &&
                            message.attachments.isNotEmpty() &&
                            message.attachments.map(ForwardAttachmentSource::attachmentIndex) ==
                            message.attachments.indices.toList()
                }
            },
        ) { "forward payload is incomplete" }
    }

    fun start() {
        if (released || started || activeJob?.isActive == true) return
        started = true
        launchAttempt(normalizedTargets)
    }

    /** Retries only failed, retryable destinations, resetting their phase and any slow notice. */
    fun retryFailed() {
        if (released || activeJob?.isActive == true || !_state.value.canRetry) return
        val failedTargets =
            _state.value.targets
                .filter { it.phase == ForwardTargetPhase.Failed }
                .filter { it.failureStage != ForwardFailureStage.PayloadTooLarge }
                .filter { it.failureStage != ForwardFailureStage.Expired }
                .filter { it.failureStage != ForwardFailureStage.SessionChanged }
                .map(ForwardTargetProgress::groupIdHex)
        if (failedTargets.isEmpty()) return
        _state.update { snapshot ->
            snapshot.copy(
                phase =
                    if (preparedMessages == null) {
                        ForwardOperationPhase.Preparing
                    } else {
                        ForwardOperationPhase.Running
                    },
                preparationSlow = false,
                targets =
                    snapshot.targets.map { target ->
                        if (target.groupIdHex in failedTargets) {
                            target.copy(phase = ForwardTargetPhase.Waiting, failureStage = null, slow = false)
                        } else {
                            target
                        }
                    },
            )
        }
        launchAttempt(failedTargets)
    }

    /** Atomically accepts cancellation only while the operation is still cancellable. */
    fun cancel() {
        val job = activeJob?.takeIf(Job::isActive) ?: return
        while (true) {
            val snapshot = _state.value
            if (!snapshot.canCancel) return
            if (_state.compareAndSet(snapshot, snapshot.copy(phase = ForwardOperationPhase.Cancelling))) break
        }
        job.cancel(CancellationException("forward cancelled"))
    }

    fun release() {
        if (released) return
        released = true
        val job = activeJob
        if (job?.isActive == true) {
            cancel()
            job.invokeOnCompletion { clearSessionState(clearRetryState = true) }
        } else {
            clearSessionState(clearRetryState = true)
        }
    }

    /** Launches one preparation/send attempt and projects every terminal cause into durable UI state. */
    @Suppress("TooGenericExceptionCaught")
    private fun launchAttempt(targets: List<String>) {
        val job =
            scope.launch {
                try {
                    val prepared =
                        preparedMessages
                            ?: withTimeoutOrNull(preparationTimeoutMillis) {
                                withSlowPhaseNotice(
                                    markSlow = { markPreparationSlow(_state) },
                                    progress = { _state.value.preparedAttachments },
                                ) {
                                    materializeMessages()
                                }
                            }
                            ?: run {
                                currentCoroutineContext().ensureActive()
                                transport.cancelStalledMaterializationBestEffort()
                                throw ForwardPreparationTimeoutException()
                            }
                    _state.update { it.copy(phase = ForwardOperationPhase.Running, preparationSlow = false) }
                    runTargets(targets, prepared)
                    finishAttempt()
                } catch (cancellation: CancellationException) {
                    if (preparedMessages == null) transport.cancelStalledMaterializationBestEffort()
                    _state.update { it.asCancelled() }
                    clearSessionState(clearRetryState = true)
                    throw cancellation
                } catch (failure: Exception) {
                    val stage =
                        when (failure) {
                            is ForwardPayloadTooLargeException -> ForwardFailureStage.PayloadTooLarge
                            is ForwardAttachmentExpiredException -> ForwardFailureStage.Expired
                            is ForwardSessionInvalidatedException -> ForwardFailureStage.SessionChanged
                            is ForwardPreparationTimeoutException -> ForwardFailureStage.PreparationTimeout
                            else -> ForwardFailureStage.Materialize
                        }
                    if (failMaterialization(stage)) onFailure(null, stage, failure)
                }
            }
        activeJob = job
        job.invokeOnCompletion {
            if (activeJob === job) activeJob = null
        }
    }

    // Structured fan-out and synchronized byte accounting must share one cancellation scope.
    private suspend fun materializeMessages(): List<PreparedForwardMessage> =
        coroutineScope {
            val accounting = ForwardMaterializationAccounting(retainedPlaintextBuffers, maxRetainedBytes)
            val materializationGate = Semaphore(targetFanout)
            val prepared =
                messages
                    .map { message ->
                        async {
                            currentCoroutineContext().ensureActive()
                            when (message) {
                                is ForwardMessagePayload.Text -> PreparedForwardMessage.Text(message.text)
                                is ForwardMessagePayload.Media ->
                                    materializeForwardMediaMessage(
                                        message = message,
                                        materializationGate = materializationGate,
                                        accounting = accounting,
                                        transport = transport,
                                        clockSeconds = clockSeconds,
                                    ) { preparedCount ->
                                        _state.update { snapshot ->
                                            val prepared = maxOf(snapshot.preparedAttachments, preparedCount)
                                            snapshot.copy(
                                                preparedAttachments = prepared,
                                                preparationSlow =
                                                    snapshot.preparationSlow &&
                                                        prepared == snapshot.preparedAttachments,
                                            )
                                        }
                                    }
                            }
                        }
                    }.awaitAll()
            preparedMessages = prepared
            prepared
        }

    /** Uploads and publishes every destination in parallel, at most [targetFanout] at a time. */
    private suspend fun runTargets(
        targetIds: List<String>,
        prepared: List<PreparedForwardMessage>,
    ) = coroutineScope {
        val fanoutGate = Semaphore(targetFanout)
        targetIds
            .map { targetId ->
                async {
                    fanoutGate.withPermit {
                        processTarget(targetId, prepared)
                    }
                }
            }.awaitAll()
    }

    /**
     * Uploads and publishes one destination, converting every non-cancellation transport failure into retry state
     * at the stage it reached. An upload that never started fails as [ForwardFailureStage.UploadTimeout].
     */
    @Suppress("LongMethod", "ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun processTarget(
        targetGroupIdHex: String,
        prepared: List<PreparedForwardMessage>,
    ) {
        var failureStage = ForwardFailureStage.Upload
        try {
            val targetReferences = uploadedReferencesByTarget.getOrPut(targetGroupIdHex) { mutableMapOf() }
            updateForwardTarget(_state, targetGroupIdHex) {
                it.copy(phase = ForwardTargetPhase.Uploading, failureStage = null, slow = false)
            }
            withSlowPhaseNotice(
                markSlow = { markForwardTargetSlow(_state, targetGroupIdHex, ForwardTargetPhase.Uploading) },
                progress = { forwardTarget(_state, targetGroupIdHex)?.uploadedAttachments },
            ) {
                prepared.forEachIndexed { messageIndex, message ->
                    currentCoroutineContext().ensureActive()
                    if (message !is PreparedForwardMessage.Media || messageIndex in targetReferences) {
                        return@forEachIndexed
                    }
                    ensureForwardAttachmentNotExpired(message.expiresAtSeconds, clockSeconds)
                    val references = transport.upload(targetGroupIdHex, message)
                    if (references.size != message.attachments.size) {
                        throw IllegalStateException("forward upload returned the wrong attachment count")
                    }
                    targetReferences[messageIndex] = references
                    updateForwardTarget(_state, targetGroupIdHex) { progress ->
                        progress.advancedTo(uploaded = targetReferences.values.sumOf { it.size })
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            failureStage = ForwardFailureStage.Publish
            updateForwardTarget(_state, targetGroupIdHex) { it.copy(phase = ForwardTargetPhase.Sending, slow = false) }
            // Display only: a slow publish is never abandoned or resent, the native result stays authoritative.
            withSlowPhaseNotice(
                markSlow = { markForwardTargetSlow(_state, targetGroupIdHex, ForwardTargetPhase.Sending) },
                progress = { forwardTarget(_state, targetGroupIdHex)?.sentMessages },
            ) {
                transport.publishBatch(
                    targetGroupIdHex = targetGroupIdHex,
                    messages = prepared,
                    uploadedReferences = targetReferences,
                    startIndex = publishedMessageCountByTarget.getValue(targetGroupIdHex),
                    clientTokenFor = { messageIndex -> publishTokens.tokenFor(targetGroupIdHex, messageIndex) },
                    onBeforeMessagePublished = { messageIndex ->
                        (prepared[messageIndex] as? PreparedForwardMessage.Media)?.let { message ->
                            ensureForwardAttachmentNotExpired(message.expiresAtSeconds, clockSeconds)
                        }
                    },
                ) { publishedIndex ->
                    val publishedCount = publishedIndex + 1
                    publishedMessageCountByTarget[targetGroupIdHex] = publishedCount
                    updateForwardTarget(_state, targetGroupIdHex) { it.advancedTo(sent = publishedCount) }
                }
            }
            updateForwardTarget(_state, targetGroupIdHex) {
                it.copy(
                    phase = ForwardTargetPhase.Completed,
                    sentMessages = totalMessageCount,
                    failureStage = null,
                    slow = false,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A rejection spent the token and may mean the destination references were encrypted at an epoch the group
            // has left, so the user's Retry admits under a fresh token after uploading that message again. Other
            // messages keep their references.
            if (failure is ForwardPublishRejectedException) {
                publishTokens.forget(targetGroupIdHex, failure.messageIndex)
                uploadedReferencesByTarget[targetGroupIdHex]?.remove(failure.messageIndex)
            }
            failureStage = targetFailureStage(failureStage, failure)
            onFailure(targetGroupIdHex, failureStage, failure)
            updateForwardTarget(_state, targetGroupIdHex) {
                it.copy(phase = ForwardTargetPhase.Failed, failureStage = failureStage, slow = false)
            }
        }
    }

    private fun finishAttempt() {
        _state.update { snapshot ->
            val complete = snapshot.targets.count { it.phase == ForwardTargetPhase.Completed }
            val failed = snapshot.targets.count { it.phase == ForwardTargetPhase.Failed }
            snapshot.copy(
                phase =
                    when {
                        complete == snapshot.targets.size -> ForwardOperationPhase.Completed
                        complete > 0 && failed > 0 -> ForwardOperationPhase.PartialFailure
                        else -> ForwardOperationPhase.Failed
                    },
            )
        }
        clearSessionState(
            clearRetryState =
                _state.value.phase == ForwardOperationPhase.Completed || !_state.value.canRetry,
        )
    }

    /** Clears sensitive plaintext and atomically marks unfinished destinations failed or cancelled. */
    private fun failMaterialization(stage: ForwardFailureStage): Boolean {
        // A retry may be re-materializing source bytes for a message that already
        // reached MDK. Preserve destination references and client tokens across
        // another transient materialization failure so a later retry re-checks
        // that token instead of sending the message again.
        _state.update { snapshot ->
            if (snapshot.phase == ForwardOperationPhase.Cancelling) {
                snapshot.asCancelled()
            } else {
                snapshot.copy(
                    phase = ForwardOperationPhase.Failed,
                    preparationSlow = false,
                    targets =
                        snapshot.targets.map { target ->
                            if (target.phase == ForwardTargetPhase.Completed) {
                                target
                            } else {
                                target.copy(phase = ForwardTargetPhase.Failed, failureStage = stage, slow = false)
                            }
                        },
                )
            }
        }
        val cancelled = _state.value.phase == ForwardOperationPhase.Cancelled
        clearSessionState(
            clearRetryState =
                cancelled ||
                    (
                        stage != ForwardFailureStage.Materialize &&
                            stage != ForwardFailureStage.PreparationTimeout
                    ),
        )
        return !cancelled
    }

    /**
     * Zeroes retained plaintext, and when [clearRetryState] the session can no longer retry, so its destination
     * references and client tokens are dropped too. Tokens survive every retryable failure, so a later Retry re-checks
     * the same token rather than minting a second message.
     */
    private fun clearSessionState(clearRetryState: Boolean) {
        retainedPlaintextBuffers.forEach { it.fill(0) }
        retainedPlaintextBuffers.clear()
        preparedMessages = null
        if (clearRetryState) {
            uploadedReferencesByTarget.clear()
            publishTokens.clear()
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val DEFAULT_FORWARD_PREPARATION_TIMEOUT_MILLIS = 120_000L
    }
}

/**
 * App-scoped owner for one visible forwarding operation. It mirrors the
 * session's live state for UI collection, performs bounded safe retries, and
 * retains terminal state until the user dismisses it or explicitly retries.
 */
internal class ForwardOperationOwner(
    private val scope: CoroutineScope,
    private val automaticRetryAttempts: Int = 3,
    private val retryDelayMillis: (attempt: Int) -> Long = { attempt -> DEFAULT_FORWARD_RETRY_DELAY_MS shl attempt },
    private val onTerminal: (ForwardOperationSnapshot) -> Unit = {},
) {
    private val _state = MutableStateFlow<ForwardOperationSnapshot?>(null)
    val state: StateFlow<ForwardOperationSnapshot?> = _state.asStateFlow()

    private var session: ForwardSession? = null
    private var stateCollector: Job? = null
    private var monitor: Job? = null

    init {
        require(automaticRetryAttempts >= 0) { "automatic retry attempts must not be negative" }
    }

    fun start(candidate: ForwardSession): Boolean {
        if (session?.state?.value?.isActive == true) return false
        clearActiveSession()
        session = candidate
        _state.value = candidate.state.value
        stateCollector =
            scope.launch {
                candidate.state.collect { snapshot ->
                    if (session === candidate) _state.value = snapshot
                }
            }
        candidate.start()
        monitor(candidate)
        return true
    }

    fun cancel(): Boolean =
        session?.takeIf { candidate -> candidate.state.value.canCancel }?.let { candidate ->
            candidate.cancel()
            true
        } ?: false

    fun retry(): Boolean {
        val candidate = session
        return if (candidate?.state?.value?.canRetry == true) {
            monitor?.cancel()
            candidate.retryFailed()
            candidate.state.value.isActive.also { started ->
                if (started) monitor(candidate)
            }
        } else {
            false
        }
    }

    fun dismiss(): Boolean {
        if (_state.value?.isActive == true) return false
        clearActiveSession()
        return true
    }

    fun release() {
        clearActiveSession()
    }

    private fun monitor(candidate: ForwardSession) {
        monitor =
            scope.launch {
                var retryCount = 0
                while (session === candidate) {
                    val snapshot = candidate.state.first { !it.isActive }
                    val retried = retryAutomatically(candidate, snapshot, retryCount)
                    if (retried) {
                        retryCount += 1
                    } else {
                        if (session === candidate) onTerminal(candidate.state.value)
                        return@launch
                    }
                }
            }
    }

    /** Retries transient destination failures while leaving preparation timeouts for explicit user action. */
    private suspend fun retryAutomatically(
        candidate: ForwardSession,
        snapshot: ForwardOperationSnapshot,
        retryCount: Int,
    ): Boolean =
        if (snapshot.canAutomaticallyRetry && retryCount < automaticRetryAttempts) {
            delay(retryDelayMillis(retryCount))
            if (session === candidate && candidate.state.value == snapshot) {
                candidate.retryFailed()
                candidate.state.value.isActive
            } else {
                false
            }
        } else {
            false
        }

    /** Stops observing the active forward, releases its session and clears the published state. */
    private fun clearActiveSession() {
        monitor?.cancel()
        stateCollector?.cancel()
        session?.release()
        monitor = null
        stateCollector = null
        session = null
        _state.value = null
    }
}

/** The stage a destination's [failure] reports: the stage it [reached], unless the failure names a more exact one. */
private fun targetFailureStage(
    reached: ForwardFailureStage,
    failure: Exception,
): ForwardFailureStage =
    when (failure) {
        is ForwardUploadStalledException -> ForwardFailureStage.UploadTimeout
        is ForwardPublishTimeoutException -> ForwardFailureStage.PublishTimeout
        is ForwardPublishRejectedException -> ForwardFailureStage.PublishRejected
        is ForwardAttachmentExpiredException -> ForwardFailureStage.Expired
        is ForwardSessionInvalidatedException -> ForwardFailureStage.SessionChanged
        else -> reached
    }

private fun ensureForwardAttachmentNotExpired(
    expiresAtSeconds: ULong?,
    clockSeconds: () -> ULong,
) {
    if (expiresAtSeconds?.takeIf { it > 0uL }?.let { it <= clockSeconds() } == true) {
        throw ForwardAttachmentExpiredException()
    }
}

/** Marks [groupIdHex] slow, but only while it is still in [phase], so a late timer never mislabels a later phase. */
private fun markForwardTargetSlow(
    state: MutableStateFlow<ForwardOperationSnapshot>,
    groupIdHex: String,
    phase: ForwardTargetPhase,
) {
    updateForwardTarget(state, groupIdHex) { target -> if (target.phase == phase) target.copy(slow = true) else target }
}

/** Marks source preparation slow, but only while the operation is still preparing. */
private fun markPreparationSlow(state: MutableStateFlow<ForwardOperationSnapshot>) {
    state.update { snapshot ->
        if (snapshot.phase == ForwardOperationPhase.Preparing) snapshot.copy(preparationSlow = true) else snapshot
    }
}

private fun updateForwardTarget(
    state: MutableStateFlow<ForwardOperationSnapshot>,
    groupIdHex: String,
    transform: (ForwardTargetProgress) -> ForwardTargetProgress,
) {
    state.update { snapshot ->
        snapshot.copy(
            targets =
                snapshot.targets.map { target ->
                    if (target.groupIdHex == groupIdHex) transform(target) else target
                },
        )
    }
}

/**
 * Auto-dismisses a forward operation's terminal Completed/Cancelled strip
 * after a short display window, unless a newer terminal snapshot superseded
 * it in the meantime. Failed and partial states stay for explicit action.
 */
internal class ForwardTerminalDismissPolicy(
    private val scope: CoroutineScope,
    private val displayDurationMillis: Long,
    private val currentSnapshot: () -> ForwardOperationSnapshot?,
    private val dismiss: () -> Unit,
) {
    private val dismissals = StalenessGuard()

    /** Schedules the delayed dismissal for one terminal snapshot. */
    fun onTerminal(snapshot: ForwardOperationSnapshot) {
        if (
            snapshot.phase != ForwardOperationPhase.Completed &&
            snapshot.phase != ForwardOperationPhase.Cancelled
        ) {
            return
        }
        val dismissGeneration = dismissals.advance()
        scope.launch {
            delay(displayDurationMillis)
            dismissals.runIfCurrent(dismissGeneration) {
                if (currentSnapshot() == snapshot) dismiss()
            }
        }
    }
}
