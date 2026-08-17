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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    val isActive: Boolean
        get() =
            phase == ForwardOperationPhase.Preparing ||
                phase == ForwardOperationPhase.Running ||
                phase == ForwardOperationPhase.Cancelling
}

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

internal class ForwardPayloadTooLargeException : IllegalArgumentException("forward payload exceeds the retained-byte limit")

internal class ForwardAttachmentExpiredException : IllegalStateException("forward attachment expired")

internal class ForwardSessionInvalidatedException : IllegalStateException("forward session account changed")

/**
 * One retryable fan-out operation. Plaintext is materialized once, while every
 * destination owns a separate uploaded-reference map. A retry resumes at that
 * destination's first unpublished message and can never reuse another chat's
 * references or duplicate an already-successful publish.
 */
internal class ForwardSession(
    private val scope: CoroutineScope,
    private val messages: List<ForwardMessagePayload>,
    targetGroupIds: List<String>,
    private val transport: ForwardTransport,
    private val maxRetainedBytes: Long = ConversationController.MEDIA_RETAINED_MAX_BYTES,
    private val targetFanout: Int = 2,
    private val clockSeconds: () -> ULong = { (System.currentTimeMillis() / 1_000L).toULong() },
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
                phase = if (totalAttachments > 0) ForwardOperationPhase.Preparing else ForwardOperationPhase.Running,
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
    private var activeJob: Job? = null
    private var released = false
    private var started = false

    init {
        require(messages.isNotEmpty()) { "forward messages must not be empty" }
        require(normalizedTargets.isNotEmpty()) { "forward targets must not be empty" }
        require(targetFanout > 0) { "target fan-out must be positive" }
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
                phase = if (preparedMessages == null) ForwardOperationPhase.Preparing else ForwardOperationPhase.Running,
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

    fun cancel() {
        val job = activeJob?.takeIf(Job::isActive) ?: return
        _state.update { it.copy(phase = ForwardOperationPhase.Cancelling) }
        job.cancel(CancellationException("forward cancelled"))
    }

    fun release() {
        if (released) return
        released = true
        val job = activeJob
        if (job?.isActive == true) {
            cancel()
            job.invokeOnCompletion { clearSensitiveState() }
        } else {
            clearSensitiveState()
        }
    }

    private fun launchAttempt(targets: List<String>) {
        val job =
            scope.launch {
                try {
                    val prepared = preparedMessages ?: materializeMessages()
                    _state.update { it.copy(phase = ForwardOperationPhase.Running) }
                    runTargets(targets, prepared)
                    finishAttempt()
                } catch (cancellation: CancellationException) {
                    _state.update { snapshot ->
                        snapshot.copy(
                            phase = ForwardOperationPhase.Cancelled,
                            targets =
                                snapshot.targets.map { target ->
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
                    }
                    clearSensitiveState()
                    throw cancellation
                } catch (tooLarge: ForwardPayloadTooLargeException) {
                    onFailure(null, ForwardFailureStage.PayloadTooLarge, tooLarge)
                    failMaterialization(ForwardFailureStage.PayloadTooLarge)
                } catch (expired: ForwardAttachmentExpiredException) {
                    onFailure(null, ForwardFailureStage.Expired, expired)
                    failMaterialization(ForwardFailureStage.Expired)
                } catch (invalidated: ForwardSessionInvalidatedException) {
                    onFailure(null, ForwardFailureStage.SessionChanged, invalidated)
                    failMaterialization(ForwardFailureStage.SessionChanged)
                } catch (failure: Exception) {
                    onFailure(null, ForwardFailureStage.Materialize, failure)
                    failMaterialization(ForwardFailureStage.Materialize)
                }
            }
        activeJob = job
        job.invokeOnCompletion {
            if (activeJob === job) activeJob = null
        }
    }

    private suspend fun materializeMessages(): List<PreparedForwardMessage> {
        var retainedBytes = 0L
        var preparedAttachmentCount = 0
        val prepared =
            messages.map { message ->
                currentCoroutineContext().ensureActive()
                when (message) {
                    is ForwardMessagePayload.Text -> PreparedForwardMessage.Text(message.text)
                    is ForwardMessagePayload.Media -> {
                        ensureNotExpired(message.expiresAtSeconds)
                        val attachments =
                            message.attachments.map { source ->
                                currentCoroutineContext().ensureActive()
                                val attachment =
                                    transport.materialize(
                                        sourceGroupIdHex = message.sourceGroupIdHex,
                                        sourceMessageIdHex = message.sourceMessageIdHex,
                                        source = source,
                                    )
                                retainedPlaintextBuffers += attachment.plaintextBytes
                                if (attachment.plaintextBytes.isEmpty()) {
                                    throw IllegalStateException("materialized attachment is empty")
                                }
                                retainedBytes += attachment.plaintextBytes.size
                                if (retainedBytes > maxRetainedBytes) throw ForwardPayloadTooLargeException()
                                preparedAttachmentCount += 1
                                _state.update { it.copy(preparedAttachments = preparedAttachmentCount) }
                                attachment
                            }
                        ensureNotExpired(message.expiresAtSeconds)
                        PreparedForwardMessage.Media(message.caption, attachments, message.expiresAtSeconds)
                    }
                }
            }
        preparedMessages = prepared
        return prepared
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

    private suspend fun processTarget(
        targetGroupIdHex: String,
        prepared: List<PreparedForwardMessage>,
    ) {
        var failureStage = ForwardFailureStage.Upload
        try {
            val targetReferences = uploadedReferencesByTarget.getOrPut(targetGroupIdHex) { mutableMapOf() }
            updateTarget(targetGroupIdHex) { it.copy(phase = ForwardTargetPhase.Uploading, failureStage = null) }
            prepared.forEachIndexed { messageIndex, message ->
                currentCoroutineContext().ensureActive()
                if (message !is PreparedForwardMessage.Media || messageIndex in targetReferences) return@forEachIndexed
                ensureNotExpired(message.expiresAtSeconds)
                val references = transport.upload(targetGroupIdHex, message)
                if (references.size != message.attachments.size) {
                    throw IllegalStateException("forward upload returned the wrong attachment count")
                }
                targetReferences[messageIndex] = references
                updateTarget(targetGroupIdHex) { progress ->
                    progress.copy(uploadedAttachments = targetReferences.values.sumOf { it.size })
                }
            }

            currentCoroutineContext().ensureActive()
            failureStage = ForwardFailureStage.Publish
            updateTarget(targetGroupIdHex) { it.copy(phase = ForwardTargetPhase.Sending) }
            transport.publishBatch(
                targetGroupIdHex = targetGroupIdHex,
                messages = prepared,
                uploadedReferences = targetReferences,
                startIndex = publishedMessageCountByTarget.getValue(targetGroupIdHex),
                onBeforeMessagePublished = { messageIndex ->
                    (prepared[messageIndex] as? PreparedForwardMessage.Media)?.let { message ->
                        ensureNotExpired(message.expiresAtSeconds)
                    }
                },
            ) { publishedIndex ->
                val publishedCount = publishedIndex + 1
                publishedMessageCountByTarget[targetGroupIdHex] = publishedCount
                updateTarget(targetGroupIdHex) { it.copy(sentMessages = publishedCount) }
            }
            updateTarget(targetGroupIdHex) {
                it.copy(
                    phase = ForwardTargetPhase.Completed,
                    sentMessages = totalMessageCount,
                    failureStage = null,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (failure is ForwardAttachmentExpiredException) failureStage = ForwardFailureStage.Expired
            if (failure is ForwardSessionInvalidatedException) failureStage = ForwardFailureStage.SessionChanged
            onFailure(targetGroupIdHex, failureStage, failure)
            updateTarget(targetGroupIdHex) { it.copy(phase = ForwardTargetPhase.Failed, failureStage = failureStage) }
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
        if (_state.value.phase == ForwardOperationPhase.Completed || !_state.value.canRetry) {
            clearSensitiveState()
        }
    }

    private fun failMaterialization(stage: ForwardFailureStage) {
        clearSensitiveState()
        _state.update { snapshot ->
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

    private fun updateTarget(
        groupIdHex: String,
        transform: (ForwardTargetProgress) -> ForwardTargetProgress,
    ) {
        _state.update { snapshot ->
            snapshot.copy(
                targets = snapshot.targets.map { if (it.groupIdHex == groupIdHex) transform(it) else it },
            )
        }
    }

    private fun ensureNotExpired(expiresAtSeconds: ULong?) {
        if (expiresAtSeconds?.takeIf { it > 0uL }?.let { it <= clockSeconds() } == true) {
            throw ForwardAttachmentExpiredException()
        }
    }

    private fun clearSensitiveState() {
        retainedPlaintextBuffers.forEach { it.fill(0) }
        retainedPlaintextBuffers.clear()
        preparedMessages = null
        uploadedReferencesByTarget.clear()
    }
}
