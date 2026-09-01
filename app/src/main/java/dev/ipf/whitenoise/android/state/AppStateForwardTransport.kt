package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Builds the production [ForwardTransport] for one forwarding operation with
 * explicit account ownership. Source attachment lookup and plaintext
 * materialization run only under [sourceAccount]; destination upload, commit
 * locking, publish, convergence recovery, and retries run only under
 * [account]. Neither boundary ever re-reads the live active account: each
 * revalidates that its own bound owner is still a signed-in signing account,
 * so removal or sign-out of either owner stops the operation without falling
 * back to another account.
 */
@Suppress("LongMethod")
internal fun WhiteNoiseAppState.forwardTransport(
    sourceAccount: String,
    account: String,
    materializationRequests: List<AttachmentTransferRequest>,
    batchMessageCount: Int,
): ForwardTransport {
    fun requireSourceAccount() {
        if (!isForwardOwnerSignedIn(sourceAccount)) throw ForwardSessionInvalidatedException()
    }

    /** Fails the session when the destination owner is no longer signed in. */
    fun requireDestinationAccount() {
        if (!isForwardOwnerSignedIn(account)) throw ForwardSessionInvalidatedException()
    }

    return object : ForwardTransport {
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
            return materializeForwardAttachment(
                source = source,
                resolveAuthoritativeReference = {
                    requireSourceAccount()
                    resolveAttachmentReference(request).also { requireSourceAccount() }
                },
                downloadPlaintext = { reference ->
                    requireSourceAccount()
                    // Forwarding owns its operation lifecycle: retain user-level queue priority
                    // without creating a durable attachment-open intent that no Worker will clear.
                    downloadAttachmentPlaintext(
                        request = request,
                        reference = reference,
                        priority = AttachmentDownloadPriority.Interactive,
                        persistInteractiveIntent = false,
                    ).also { requireSourceAccount() }
                },
            )
        }

        /** Stops stale shared source attempts so an explicit retry creates fresh work. */
        override fun cancelStalledMaterialization() {
            materializationRequests.forEach(::cancelMemoizedAttachmentDownload)
        }

        override suspend fun upload(
            targetGroupIdHex: String,
            message: PreparedForwardMessage.Media,
        ): List<MediaAttachmentReferenceFfi> {
            requireDestinationAccount()
            val uploaded =
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
            requireDestinationAccount()
            return uploaded
        }

        private suspend fun recentForwardTimeline(
            targetGroupIdHex: String,
            batchMessageCount: Int,
        ): List<TimelineMessageRecordFfi> {
            requireDestinationAccount()
            val limit = maxOf(100u, batchMessageCount.toUInt() + 100u)
            return marmotIo {
                timelineMessages(
                    account,
                    TimelineMessageQueryFfi(
                        groupIdHex = targetGroupIdHex,
                        search = null,
                        before = null,
                        beforeMessageId = null,
                        after = null,
                        afterMessageId = null,
                        limit = limit,
                    ),
                ).messages
            }.also { requireDestinationAccount() }
        }

        @Suppress("LongMethod", "ThrowsCount", "TooGenericExceptionCaught")
        override suspend fun publishBatch(
            targetGroupIdHex: String,
            messages: List<PreparedForwardMessage>,
            uploadedReferences: Map<Int, List<MediaAttachmentReferenceFfi>>,
            startIndex: Int,
            onBeforeMessagePublished: (messageIndex: Int) -> Unit,
            onMessagePublished: (messageIndex: Int) -> Unit,
        ) {
            requireDestinationAccount()
            withGroupCommitLock(account, targetGroupIdHex) {
                val knownMessageIds =
                    recentForwardTimeline(targetGroupIdHex, messages.size)
                        .mapTo(mutableSetOf(), TimelineMessageRecordFfi::messageIdHex)
                for (messageIndex in startIndex until messages.size) {
                    currentCoroutineContext().ensureActive()
                    onBeforeMessagePublished(messageIndex)
                    requireDestinationAccount()
                    val message = messages[messageIndex]
                    val references = uploadedReferences[messageIndex].orEmpty()
                    val evidenceBefore = knownMessageIds.toSet()
                    try {
                        val publishedMessageIds =
                            when (message) {
                                is PreparedForwardMessage.Text ->
                                    marmotIo { sendText(account, targetGroupIdHex, message.text) }.messageIds
                                is PreparedForwardMessage.Media -> {
                                    check(references.isNotEmpty()) { "missing destination media references" }
                                    marmotIo {
                                        sendMediaAttachments(
                                            account,
                                            targetGroupIdHex,
                                            references,
                                            message.caption,
                                        )
                                    }.messageIds
                                }
                            }
                        knownMessageIds += publishedMessageIds
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        val recoveryEvidence =
                            ForwardPublishRecoveryEvidence(
                                messageIndex = messageIndex,
                                knownMessageIdsBefore = evidenceBefore,
                                pendingMessageIdHex = null,
                            )
                        val timelineAfter =
                            try {
                                recentForwardTimeline(targetGroupIdHex, messages.size)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (evidenceFailure: Exception) {
                                failure.addSuppressed(evidenceFailure)
                                throw ForwardPublishUncertainException(recoveryEvidence, failure)
                            }
                        val projectionsAfter = forwardProjectionRecords(timelineAfter, message, references)
                        val newProjectionIds = projectionsAfter.keys - evidenceBefore
                        val newProjection = newProjectionIds.singleOrNull()?.let(projectionsAfter::get)
                        when {
                            newProjection?.sourceMessageIdHex != null &&
                                newProjection.invalidationStatus == null ->
                                knownMessageIds += newProjection.messageIdHex
                            newProjection != null &&
                                !newProjection.deleted &&
                                newProjection.invalidationStatus == null ->
                                throw ForwardPublishUncertainException(
                                    recoveryEvidence.copy(pendingMessageIdHex = newProjection.messageIdHex),
                                    cause = failure,
                                )
                            newProjectionIds.isEmpty() ->
                                throw ForwardPublishNotCommittedException(failure)
                            else ->
                                throw ForwardPublishUncertainException(recoveryEvidence, failure)
                        }
                    }
                    onMessagePublished(messageIndex)
                }
            }
        }

        @Suppress("ThrowsCount")
        override suspend fun recoverPendingPublish(
            targetGroupIdHex: String,
            message: PreparedForwardMessage,
            uploadedReferences: List<MediaAttachmentReferenceFfi>,
            evidence: ForwardPublishRecoveryEvidence,
        ): ForwardPublishRecoveryResult {
            requireDestinationAccount()
            val recovered =
                withGroupCommitLock(account, targetGroupIdHex) {
                    val timeline =
                        try {
                            recentForwardTimeline(targetGroupIdHex, batchMessageCount)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (invalidated: ForwardSessionInvalidatedException) {
                            throw invalidated
                        } catch (_: Exception) {
                            return@withGroupCommitLock ForwardPublishRecoveryResult.Unavailable
                        }
                    val newProjections =
                        forwardProjectionRecords(timeline, message, uploadedReferences)
                            .filterKeys { it !in evidence.knownMessageIdsBefore }
                    val candidate =
                        evidence.pendingMessageIdHex
                            ?.let { pendingId -> newProjections[pendingId] }
                            ?: newProjections.values.singleOrNull()
                    if (candidate == null) {
                        return@withGroupCommitLock if (
                            evidence.pendingMessageIdHex == null && newProjections.isEmpty()
                        ) {
                            ForwardPublishRecoveryResult.NotCommitted
                        } else {
                            ForwardPublishRecoveryResult.Unavailable
                        }
                    }
                    if (candidate.invalidationStatus != null || candidate.deleted) {
                        return@withGroupCommitLock ForwardPublishRecoveryResult.Unavailable
                    }
                    if (candidate.sourceMessageIdHex != null) {
                        return@withGroupCommitLock ForwardPublishRecoveryResult.Published
                    }
                    marmotIo { retryGroupConvergence(account, targetGroupIdHex) }
                    val delivered =
                        try {
                            recentForwardTimeline(targetGroupIdHex, batchMessageCount)
                                .firstOrNull { it.messageIdHex == candidate.messageIdHex }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (invalidated: ForwardSessionInvalidatedException) {
                            throw invalidated
                        } catch (_: Exception) {
                            null
                        }
                    if (delivered?.sourceMessageIdHex != null && delivered.invalidationStatus == null) {
                        ForwardPublishRecoveryResult.Published
                    } else {
                        ForwardPublishRecoveryResult.Unavailable
                    }
                }
            requireDestinationAccount()
            return recovered
        }
    }
}
