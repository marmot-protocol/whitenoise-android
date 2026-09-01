package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Immutable
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
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
    Publish,
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
)

@Immutable
internal data class ForwardOperationSnapshot(
    val phase: ForwardOperationPhase,
    val preparedAttachments: Int,
    val totalAttachments: Int,
    val targets: List<ForwardTargetProgress>,
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

    /** Timeout is user-recoverable but must not immediately re-enter the same stalled source work. */
    val canAutomaticallyRetry: Boolean
        get() =
            canRetry &&
                targets.none { target -> target.failureStage == ForwardFailureStage.PreparationTimeout }
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
        targets =
            targets.map { target ->
                if (
                    target.phase == ForwardTargetPhase.Completed ||
                    target.phase == ForwardTargetPhase.Failed
                ) {
                    target
                } else {
                    target.copy(phase = ForwardTargetPhase.Cancelled)
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

    suspend fun publishBatch(
        targetGroupIdHex: String,
        messages: List<PreparedForwardMessage>,
        uploadedReferences: Map<Int, List<MediaAttachmentReferenceFfi>>,
        startIndex: Int,
        onBeforeMessagePublished: (messageIndex: Int) -> Unit,
        onMessagePublished: (messageIndex: Int) -> Unit,
    )

    /** Resolve an ambiguous publish without minting a duplicate event. */
    suspend fun recoverPendingPublish(
        targetGroupIdHex: String,
        message: PreparedForwardMessage,
        uploadedReferences: List<MediaAttachmentReferenceFfi>,
        evidence: ForwardPublishRecoveryEvidence,
    ): ForwardPublishRecoveryResult
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

internal data class ForwardPublishRecoveryEvidence(
    val messageIndex: Int,
    val knownMessageIdsBefore: Set<String>,
    val pendingMessageIdHex: String?,
)

internal enum class ForwardPublishRecoveryResult {
    Published,
    NotCommitted,
    Unavailable,
}

internal class ForwardPublishUncertainException(
    val evidence: ForwardPublishRecoveryEvidence,
    cause: Throwable,
) : IllegalStateException("forward publish result is uncertain", cause)

internal class ForwardPublishNotCommittedException(
    cause: Throwable,
) : IllegalStateException("forward publish failed before a local commit", cause)

private const val FORWARD_PUBLISH_RECOVERY_UNAVAILABLE = "forward pending publish could not be recovered"

internal class ForwardPublishRecoveryUnavailableException : IllegalStateException(FORWARD_PUBLISH_RECOVERY_UNAVAILABLE)

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
    private val uncertainPublishByTarget = mutableMapOf<String, ForwardPublishRecoveryEvidence>()
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
                targets =
                    snapshot.targets.map { target ->
                        if (target.groupIdHex in failedTargets) {
                            target.copy(phase = ForwardTargetPhase.Waiting, failureStage = null)
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
                            ?: withTimeoutOrNull(preparationTimeoutMillis) { materializeMessages() }
                            ?: run {
                                currentCoroutineContext().ensureActive()
                                transport.cancelStalledMaterializationBestEffort()
                                throw ForwardPreparationTimeoutException()
                            }
                    _state.update { it.copy(phase = ForwardOperationPhase.Running) }
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
                                            snapshot.copy(
                                                preparedAttachments =
                                                    maxOf(snapshot.preparedAttachments, preparedCount),
                                            )
                                        }
                                    }
                            }
                        }
                    }.awaitAll()
            preparedMessages = prepared
            prepared
        }

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

    // Each destination converts all non-cancellation transport failures into retry state.
    @Suppress("LongMethod", "ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun processTarget(
        targetGroupIdHex: String,
        prepared: List<PreparedForwardMessage>,
    ) {
        var failureStage = ForwardFailureStage.Upload
        try {
            val targetReferences = uploadedReferencesByTarget.getOrPut(targetGroupIdHex) { mutableMapOf() }
            updateForwardTarget(_state, targetGroupIdHex) {
                it.copy(phase = ForwardTargetPhase.Uploading, failureStage = null)
            }
            prepared.forEachIndexed { messageIndex, message ->
                currentCoroutineContext().ensureActive()
                if (message !is PreparedForwardMessage.Media || messageIndex in targetReferences) return@forEachIndexed
                ensureForwardAttachmentNotExpired(message.expiresAtSeconds, clockSeconds)
                val references = transport.upload(targetGroupIdHex, message)
                if (references.size != message.attachments.size) {
                    throw IllegalStateException("forward upload returned the wrong attachment count")
                }
                targetReferences[messageIndex] = references
                updateForwardTarget(_state, targetGroupIdHex) { progress ->
                    progress.copy(uploadedAttachments = targetReferences.values.sumOf { it.size })
                }
            }

            currentCoroutineContext().ensureActive()
            failureStage = ForwardFailureStage.Publish
            updateForwardTarget(_state, targetGroupIdHex) { it.copy(phase = ForwardTargetPhase.Sending) }
            uncertainPublishByTarget[targetGroupIdHex]?.let { evidence ->
                val message = prepared[evidence.messageIndex]
                val recovery =
                    transport.recoverPendingPublish(
                        targetGroupIdHex = targetGroupIdHex,
                        message = message,
                        uploadedReferences = targetReferences[evidence.messageIndex].orEmpty(),
                        evidence = evidence,
                    )
                when (recovery) {
                    ForwardPublishRecoveryResult.Published -> {
                        val recoveredCount = evidence.messageIndex + 1
                        publishedMessageCountByTarget[targetGroupIdHex] = recoveredCount
                        uncertainPublishByTarget.remove(targetGroupIdHex)
                        updateForwardTarget(_state, targetGroupIdHex) { it.copy(sentMessages = recoveredCount) }
                    }
                    ForwardPublishRecoveryResult.NotCommitted -> uncertainPublishByTarget.remove(targetGroupIdHex)
                    ForwardPublishRecoveryResult.Unavailable -> throw ForwardPublishRecoveryUnavailableException()
                }
            }
            transport.publishBatch(
                targetGroupIdHex = targetGroupIdHex,
                messages = prepared,
                uploadedReferences = targetReferences,
                startIndex = publishedMessageCountByTarget.getValue(targetGroupIdHex),
                onBeforeMessagePublished = { messageIndex ->
                    (prepared[messageIndex] as? PreparedForwardMessage.Media)?.let { message ->
                        ensureForwardAttachmentNotExpired(message.expiresAtSeconds, clockSeconds)
                    }
                },
            ) { publishedIndex ->
                val publishedCount = publishedIndex + 1
                publishedMessageCountByTarget[targetGroupIdHex] = publishedCount
                updateForwardTarget(_state, targetGroupIdHex) { it.copy(sentMessages = publishedCount) }
            }
            updateForwardTarget(_state, targetGroupIdHex) {
                it.copy(
                    phase = ForwardTargetPhase.Completed,
                    sentMessages = totalMessageCount,
                    failureStage = null,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (failure is ForwardPublishUncertainException) {
                uncertainPublishByTarget[targetGroupIdHex] = failure.evidence
            }
            if (failure is ForwardAttachmentExpiredException) failureStage = ForwardFailureStage.Expired
            if (failure is ForwardSessionInvalidatedException) failureStage = ForwardFailureStage.SessionChanged
            onFailure(targetGroupIdHex, failureStage, failure)
            updateForwardTarget(_state, targetGroupIdHex) {
                it.copy(phase = ForwardTargetPhase.Failed, failureStage = failureStage)
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
        // A retry may be re-materializing source bytes for an earlier ambiguous
        // publish. Preserve destination references and recovery evidence across
        // another transient materialization failure so a later retry cannot
        // duplicate that uncertain message.
        _state.update { snapshot ->
            if (snapshot.phase == ForwardOperationPhase.Cancelling) {
                snapshot.asCancelled()
            } else {
                snapshot.copy(
                    phase = ForwardOperationPhase.Failed,
                    targets =
                        snapshot.targets.map { target ->
                            if (target.phase == ForwardTargetPhase.Completed) {
                                target
                            } else {
                                target.copy(phase = ForwardTargetPhase.Failed, failureStage = stage)
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

    private fun clearSessionState(clearRetryState: Boolean) {
        retainedPlaintextBuffers.forEach { it.fill(0) }
        retainedPlaintextBuffers.clear()
        preparedMessages = null
        if (clearRetryState) {
            uploadedReferencesByTarget.clear()
            uncertainPublishByTarget.clear()
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

private fun ensureForwardAttachmentNotExpired(
    expiresAtSeconds: ULong?,
    clockSeconds: () -> ULong,
) {
    if (expiresAtSeconds?.takeIf { it > 0uL }?.let { it <= clockSeconds() } == true) {
        throw ForwardAttachmentExpiredException()
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

private const val FORWARD_CHAT_MESSAGE_KIND = 9uL

/**
 * Indexes sent timeline records matching one prepared message's content, used
 * to recognize a forward's own publish when its result was uncertain.
 */
internal fun forwardProjectionRecords(
    timeline: List<TimelineMessageRecordFfi>,
    message: PreparedForwardMessage,
    references: List<MediaAttachmentReferenceFfi>,
): Map<String, TimelineMessageRecordFfi> =
    timeline
        .asSequence()
        .filter { record ->
            record.direction == "sent" &&
                record.kind == FORWARD_CHAT_MESSAGE_KIND &&
                when (message) {
                    is PreparedForwardMessage.Text ->
                        record.plaintext == message.text && record.media.isEmpty()
                    is PreparedForwardMessage.Media ->
                        record.plaintext == message.caption.orEmpty() &&
                            record.media.map { it.ciphertextSha256 } ==
                            references.map { it.ciphertextSha256 }
                }
        }.associateBy(TimelineMessageRecordFfi::messageIdHex)

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
