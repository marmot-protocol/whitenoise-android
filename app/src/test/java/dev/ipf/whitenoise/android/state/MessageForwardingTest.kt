package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.core.ForwardAttachmentSource
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageForwardingTest {
    @Test
    fun cancelledOperationNeverOffersRetryForEarlierFailures() {
        val snapshot =
            ForwardOperationSnapshot(
                phase = ForwardOperationPhase.Cancelled,
                preparedAttachments = 1,
                totalAttachments = 1,
                targets =
                    listOf(
                        ForwardTargetProgress(
                            groupIdHex = "target",
                            phase = ForwardTargetPhase.Failed,
                            totalAttachments = 1,
                            totalMessages = 1,
                            failureStage = ForwardFailureStage.Upload,
                        ),
                    ),
            )

        assertFalse(snapshot.canRetry)
    }

    @Test
    fun mediaIsMaterializedOnceAndUploadedFreshForEveryTarget() =
        runTest {
            val transport = RecordingForwardTransport()
            val session =
                ForwardSession(
                    scope = this,
                    messages =
                        listOf(
                            mediaPayload("media", "caption", "photo.jpg", "notes.pdf"),
                            textPayload("text", "after media"),
                        ),
                    targetGroupIds = listOf("target-a", "target-b", "target-a"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(listOf(0, 1), transport.materializedIndices)
            assertEquals(listOf("target-a", "target-b"), transport.uploadTargets.sorted())
            assertEquals(
                listOf("target-a" to 0, "target-a" to 1, "target-b" to 0, "target-b" to 1),
                transport.published.sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second }),
            )
            assertTrue(transport.publishedMediaHashes.getValue("target-a").all { it.startsWith("target-a-") })
            assertTrue(transport.publishedMediaHashes.getValue("target-b").all { it.startsWith("target-b-") })
            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)

            session.start()
            advanceUntilIdle()
            assertEquals(2, transport.uploadTargets.size)
            assertEquals(4, transport.published.size)
        }

    @Test
    fun retryRunsOnlyFailedTargetsWithoutDuplicatingSuccessfulSends() =
        runTest {
            val transport = RecordingForwardTransport(failUploadOnceFor = mutableSetOf("target-b"))
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg")),
                    targetGroupIds = listOf("target-a", "target-b"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.PartialFailure, session.state.value.phase)
            assertEquals(listOf("target-a" to 0), transport.published)

            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(1, transport.uploadTargets.count { it == "target-a" })
            assertEquals(2, transport.uploadTargets.count { it == "target-b" })
            assertEquals(listOf("target-a" to 0, "target-b" to 0), transport.published)
        }

    @Test
    fun publishOnlyRetryReusesTargetUploadsAndResumesAtFirstUnpublishedMessage() =
        runTest {
            val transport = RecordingForwardTransport(failPublishUncertainOnceFor = mutableSetOf("target"))
            val session =
                ForwardSession(
                    scope = this,
                    messages =
                        listOf(
                            textPayload("first", "first"),
                            mediaPayload("second", "caption", "photo.jpg"),
                        ),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(listOf(0), transport.publishStartIndices)
            assertEquals(listOf("target" to 0), transport.published)
            assertEquals(1, transport.uploadTargets.size)

            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(listOf(0, 2), transport.publishStartIndices)
            assertEquals(listOf("target"), transport.convergenceTargets)
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
            assertEquals(1, transport.uploadTargets.size)
        }

    @Test
    fun failedConvergenceNeverResendsAnUncertainMessage() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    failPublishUncertainOnceFor = mutableSetOf("target"),
                    recoverPendingPublish = false,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(textPayload("first", "first"), textPayload("second", "second")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(listOf(0), transport.publishStartIndices)
            assertEquals(listOf("target"), transport.convergenceTargets)
            assertEquals(listOf("target" to 0), transport.published)
        }

    @Test
    fun provenPreCommitFailureRetriesTheSameIndexWithoutConvergence() =
        runTest {
            val transport = RecordingForwardTransport(failPublishBeforeCommitOnceFor = mutableSetOf("target"))
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(textPayload("first", "first"), textPayload("second", "second")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(listOf(0, 1), transport.publishStartIndices)
            assertTrue(transport.convergenceTargets.isEmpty())
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
        }

    @Test
    fun missingPendingCommitEvidenceNeverConvergesOrResends() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    failPublishUncertainOnceFor = mutableSetOf("target"),
                    uncertainPendingMessageId = null,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(textPayload("first", "first"), textPayload("second", "second")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(listOf(0), transport.publishStartIndices)
            assertTrue(transport.convergenceTargets.isEmpty())
            assertEquals(listOf("target" to 0), transport.published)
        }

    @Test
    fun cancellationWhileUploadingPublishesNothing() =
        runTest {
            val uploadGate = CompletableDeferred<Unit>()
            val transport = RecordingForwardTransport(uploadGate = uploadGate)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            runCurrent()
            session.cancel()
            advanceUntilIdle()

            assertEquals(emptyList<Pair<String, Int>>(), transport.published)
            assertEquals(ForwardOperationPhase.Cancelled, session.state.value.phase)
        }

    @Test
    fun releaseWhileUploadingCancelsAndClearsRetainedPlaintext() =
        runTest {
            val uploadGate = CompletableDeferred<Unit>()
            val retained = byteArrayOf(7, 8, 9)
            val transport = RecordingForwardTransport(uploadGate = uploadGate, materializedBytes = retained)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            runCurrent()
            session.release()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Cancelled, session.state.value.phase)
            assertTrue(retained.contentEquals(byteArrayOf(0, 0, 0)))
            assertTrue(transport.published.isEmpty())
        }

    @Test
    fun materializationUsesTheSameBoundedFanoutAsTargetWork() =
        runTest {
            val materializeGate = CompletableDeferred<Unit>()
            val transport = RecordingForwardTransport(materializeGate = materializeGate)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "one.bin", "two.bin", "three.bin")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    targetFanout = 2,
                )

            session.start()
            runCurrent()

            assertEquals(2, transport.materializedIndices.size)
            materializeGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(3, transport.materializedIndices.size)
            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
        }

    @Test
    fun oversizedMaterializedBatchFailsBeforeAnyUpload() =
        runTest {
            val transport = RecordingForwardTransport(materializedByteCount = 4)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "one.bin", "two.bin")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    maxRetainedBytes = 7,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(
                ForwardFailureStage.PayloadTooLarge,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertTrue(transport.uploadTargets.isEmpty())
            assertTrue(transport.published.isEmpty())
            assertFalse(session.state.value.canRetry)
        }

    @Test
    fun attachmentExpiringAfterUploadIsNeverPublishedOrRetried() =
        runTest {
            var now = 100uL
            val transport = RecordingForwardTransport(onUpload = { now = 101uL })
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg").copy(expiresAtSeconds = 101uL)),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    clockSeconds = { now },
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(
                ForwardFailureStage.Expired,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertEquals(listOf("target"), transport.uploadTargets)
            assertTrue(transport.published.isEmpty())
            assertFalse(session.state.value.canRetry)
        }

    @Test
    fun optimisticSourceResolvesAuthoritativeMetadataBeforeDownload() =
        runTest {
            val optimistic = reference("draft.jpg", "optimistic", sourceEpoch = 0uL)
            val authoritative =
                reference("final.jpg", "authoritative").copy(
                    mediaType = "image/jpeg",
                    dim = "1280x720",
                    thumbhash = "thumb",
                )
            var downloadedReference: MediaAttachmentReferenceFfi? = null
            val sourceCacheBytes = byteArrayOf(1, 2, 3)

            val prepared =
                materializeForwardAttachment(
                    source = ForwardAttachmentSource(0, optimistic),
                    resolveAuthoritativeReference = { authoritative },
                    downloadPlaintext = { reference ->
                        downloadedReference = reference
                        sourceCacheBytes
                    },
                )

            assertEquals(authoritative, downloadedReference)
            assertEquals("final.jpg", prepared.fileName)
            assertEquals("image/jpeg", prepared.mediaType)
            assertEquals("1280x720", prepared.dim)
            assertEquals("thumb", prepared.thumbhash)
            assertTrue(prepared.plaintextBytes.contentEquals(byteArrayOf(1, 2, 3)))
            prepared.plaintextBytes.fill(0)
            assertTrue(sourceCacheBytes.contentEquals(byteArrayOf(1, 2, 3)))
        }

    @Test
    fun authoritativeSourceSkipsResolutionAndMissingProjectionFailsBeforeDownload() =
        runTest {
            var resolved = false
            val authoritative = reference("ready.pdf", "ready")
            materializeForwardAttachment(
                source = ForwardAttachmentSource(0, authoritative),
                resolveAuthoritativeReference = {
                    resolved = true
                    null
                },
                downloadPlaintext = { byteArrayOf(1) },
            )
            assertFalse(resolved)

            var downloaded = false
            try {
                materializeForwardAttachment(
                    source = ForwardAttachmentSource(0, reference("draft.pdf", "draft", sourceEpoch = 0uL)),
                    resolveAuthoritativeReference = { null },
                    downloadPlaintext = {
                        downloaded = true
                        byteArrayOf(1)
                    },
                )
                fail("Expected unresolved optimistic reference to fail")
            } catch (_: AttachmentReferenceNotReadyException) {
                assertFalse(downloaded)
            }
        }

    @Test
    fun completedSessionClearsRetainedPlaintext() =
        runTest {
            val retained = byteArrayOf(4, 5, 6)
            val transport = RecordingForwardTransport(materializedBytes = retained)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertTrue(retained.contentEquals(byteArrayOf(0, 0, 0)))
        }

    @Test
    fun operationOwnerPublishesLiveStateAndCancelsBeforeSending() =
        runTest {
            val uploadGate = CompletableDeferred<Unit>()
            val transport = RecordingForwardTransport(uploadGate = uploadGate)
            val owner = ForwardOperationOwner(scope = this, automaticRetryAttempts = 0)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", "caption", "photo.jpg")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            assertTrue(owner.start(session))
            runCurrent()
            assertEquals(
                ForwardTargetPhase.Uploading,
                owner.state.value
                    ?.targets
                    ?.single()
                    ?.phase,
            )
            assertTrue(owner.cancel())
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Cancelled, owner.state.value?.phase)
            assertTrue(transport.published.isEmpty())
            assertTrue(owner.dismiss())
            assertEquals(null, owner.state.value)
        }

    @Test
    fun operationOwnerRetriesOnlyFailedTargetsAndRetainsTerminalState() =
        runTest {
            val terminalSnapshots = mutableListOf<ForwardOperationSnapshot>()
            val transport = RecordingForwardTransport(failUploadOnceFor = mutableSetOf("target-b"))
            val owner =
                ForwardOperationOwner(
                    scope = this,
                    automaticRetryAttempts = 0,
                    onTerminal = terminalSnapshots::add,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg")),
                    targetGroupIds = listOf("target-a", "target-b"),
                    transport = transport,
                )

            assertTrue(owner.start(session))
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.PartialFailure, owner.state.value?.phase)
            assertTrue(owner.retry())
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, owner.state.value?.phase)
            assertEquals(1, transport.uploadTargets.count { it == "target-a" })
            assertEquals(2, transport.uploadTargets.count { it == "target-b" })
            assertEquals(listOf("target-a" to 0, "target-b" to 0), transport.published)
            assertEquals(
                listOf(ForwardOperationPhase.PartialFailure, ForwardOperationPhase.Completed),
                terminalSnapshots.map(ForwardOperationSnapshot::phase),
            )
            owner.release()
        }

    @Test
    fun operationCannotBeCancelledAfterPublishingBegins() =
        runTest {
            val snapshot =
                ForwardOperationSnapshot(
                    phase = ForwardOperationPhase.Running,
                    preparedAttachments = 0,
                    totalAttachments = 0,
                    targets =
                        listOf(
                            ForwardTargetProgress(
                                groupIdHex = "target",
                                phase = ForwardTargetPhase.Sending,
                                totalAttachments = 0,
                                totalMessages = 2,
                            ),
                        ),
                )

            assertFalse(snapshot.canCancel)
        }

    private class RecordingForwardTransport(
        private val failUploadOnceFor: MutableSet<String> = mutableSetOf(),
        private val failPublishUncertainOnceFor: MutableSet<String> = mutableSetOf(),
        private val failPublishBeforeCommitOnceFor: MutableSet<String> = mutableSetOf(),
        private val uploadGate: CompletableDeferred<Unit>? = null,
        private val materializeGate: CompletableDeferred<Unit>? = null,
        private val materializedByteCount: Int = 1,
        private val materializedBytes: ByteArray? = null,
        private val onUpload: () -> Unit = {},
        private val recoverPendingPublish: Boolean = true,
        private val uncertainPendingMessageId: String? = "pending-forward",
    ) : ForwardTransport {
        val materializedIndices = mutableListOf<Int>()
        val uploadTargets = mutableListOf<String>()
        val publishStartIndices = mutableListOf<Int>()
        val convergenceTargets = mutableListOf<String>()
        val published = mutableListOf<Pair<String, Int>>()
        val publishedMediaHashes = mutableMapOf<String, List<String>>()

        override suspend fun materialize(
            sourceGroupIdHex: String,
            sourceMessageIdHex: String,
            source: ForwardAttachmentSource,
        ): PendingAttachment {
            materializedIndices += source.attachmentIndex
            materializeGate?.await()
            return PendingAttachment(
                plaintextBytes =
                    materializedBytes
                        ?: ByteArray(materializedByteCount) { source.attachmentIndex.toByte() },
                mediaType = source.reference.mediaType,
                fileName = source.reference.fileName,
                dim = source.reference.dim,
                thumbhash = source.reference.thumbhash,
            )
        }

        override suspend fun upload(
            targetGroupIdHex: String,
            message: PreparedForwardMessage.Media,
        ): List<MediaAttachmentReferenceFfi> {
            uploadTargets += targetGroupIdHex
            onUpload()
            uploadGate?.await()
            if (failUploadOnceFor.remove(targetGroupIdHex)) error("upload failed")
            return message.attachments.mapIndexed { index, attachment ->
                reference(
                    fileName = attachment.fileName,
                    hashPrefix = "$targetGroupIdHex-$index",
                )
            }
        }

        override suspend fun publishBatch(
            targetGroupIdHex: String,
            messages: List<PreparedForwardMessage>,
            uploadedReferences: Map<Int, List<MediaAttachmentReferenceFfi>>,
            startIndex: Int,
            onBeforeMessagePublished: (messageIndex: Int) -> Unit,
            onMessagePublished: (messageIndex: Int) -> Unit,
        ) {
            publishStartIndices += startIndex
            for (index in startIndex until messages.size) {
                currentCoroutineContext().ensureActive()
                onBeforeMessagePublished(index)
                if (index == 1 && failPublishBeforeCommitOnceFor.remove(targetGroupIdHex)) {
                    throw ForwardPublishNotCommittedException(IllegalStateException("publish rejected"))
                }
                if (index == 1 && failPublishUncertainOnceFor.remove(targetGroupIdHex)) {
                    throw ForwardPublishUncertainException(
                        messageIndex = index,
                        pendingMessageIdHex = uncertainPendingMessageId,
                        cause = IllegalStateException("publish failed"),
                    )
                }
                val message = messages[index]
                if (message is PreparedForwardMessage.Media) {
                    publishedMediaHashes[targetGroupIdHex] =
                        uploadedReferences.getValue(index).map(MediaAttachmentReferenceFfi::ciphertextSha256)
                }
                published += targetGroupIdHex to index
                onMessagePublished(index)
            }
        }

        override suspend fun recoverPendingPublish(
            targetGroupIdHex: String,
            pendingMessageIdHex: String,
        ): Boolean {
            convergenceTargets += targetGroupIdHex
            if (recoverPendingPublish) published += targetGroupIdHex to 1
            return recoverPendingPublish
        }
    }

    private fun textPayload(
        id: String,
        text: String,
    ) = ForwardMessagePayload.Text("source", id, text)

    private fun mediaPayload(
        id: String,
        caption: String?,
        vararg fileNames: String,
    ) = ForwardMessagePayload.Media(
        sourceGroupIdHex = "source",
        sourceMessageIdHex = id,
        caption = caption,
        attachments =
            fileNames.mapIndexed { index, fileName ->
                ForwardAttachmentSource(index, reference(fileName, "source-$index"))
            },
    )

    private companion object {
        fun reference(
            fileName: String,
            hashPrefix: String,
            sourceEpoch: ULong = 7uL,
        ) = MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi("blossom-v1", "https://media.example/$hashPrefix")),
            ciphertextSha256 = "$hashPrefix-${"a".repeat(64)}",
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = fileName,
            mediaType = "application/octet-stream",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = sourceEpoch,
            dim = null,
            thumbhash = null,
        )
    }
}
