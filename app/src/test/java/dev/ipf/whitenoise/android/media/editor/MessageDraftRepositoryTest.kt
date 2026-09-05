package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One fixture exercises the repository's serialized mutation and generation race matrix.
class MessageDraftRepositoryTest {
    @Test
    fun coalescedWriterPersistsOnlyLatestTextFromKeystrokeBurst() =
        runTest {
            val gateway = FakeDraftGateway(null)
            val writer =
                CoalescingMessageDraftWriter(
                    scope = this,
                    drafts =
                        MessageDraftRepository(
                            gateway = gateway,
                            editorSessions = EditorSessionStore(RepositorySessionStrings()),
                            ioDispatcher = StandardTestDispatcher(testScheduler),
                        ),
                    debounceMillis = 250,
                )

            writer.submit(ACCOUNT, GROUP, "h")
            writer.submit(ACCOUNT, GROUP, "he")
            writer.submit(ACCOUNT, GROUP, "hello")
            runCurrent()
            advanceTimeBy(249)
            runCurrent()
            assertEquals(0, gateway.saveCalls)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, gateway.saveCalls)
            assertEquals("hello", gateway.current?.content)
        }

    /** Verifies a superseded save cannot publish either stale content or its stale update time. */
    @Test
    fun coalescedWriterDoesNotPublishStaleCompletionAfterNewerEditIsAccepted() =
        runTest {
            val gateway = FakeDraftGateway(null)
            val published = mutableListOf<Pair<String, Long?>>()
            lateinit var writer: CoalescingMessageDraftWriter
            gateway.onSave = { content ->
                if (content == "first") writer.submit(ACCOUNT, GROUP, "second")
            }
            writer =
                CoalescingMessageDraftWriter(
                    scope = this,
                    drafts = repository(gateway),
                    debounceMillis = 0,
                    onResult = { _, _, content, result ->
                        val updatedAt = (result as? MessageDraftMutationResult.Success)?.draft?.updatedAtMs
                        published += content to updatedAt
                    },
                )

            writer.submit(ACCOUNT, GROUP, "first")
            runCurrent()
            writer.flush()

            assertEquals(listOf("second" to 2L), published)
            assertEquals("second", gateway.current?.content)
        }

    @Test
    fun successfulSendCleanupDoesNotDeleteTextAcceptedBeforeCleanupRuns() =
        runTest {
            val gateway = FakeDraftGateway(null)
            val writer = writer(gateway)
            writer.submit(ACCOUNT, GROUP, "sent")
            writer.flush()
            val sentGeneration = writer.generation(ACCOUNT, GROUP)

            writer.submit(ACCOUNT, GROUP, "next draft")
            val result = writer.deleteIfCurrent(ACCOUNT, GROUP, sentGeneration)
            writer.flush()

            assertEquals(MessageDraftConditionalDeleteResult.Superseded, result)
            assertEquals("next draft", gateway.current?.content)
        }

    @Test
    fun successfulSendCleanupInvalidatesAuthoritativeHydrationAlreadyInFlight() =
        runTest {
            val gateway = FakeDraftGateway(draft(content = "sent"))
            val writer = writer(gateway)
            val sentGeneration = writer.generation(ACCOUNT, GROUP)
            var cleanupGeneration: MessageDraftGeneration? = null
            gateway.onRead = {
                cleanupGeneration =
                    writer.beginSuccessfulSendCleanup(
                        accountRef = ACCOUNT,
                        groupIdHex = GROUP,
                        sentGeneration = sentGeneration,
                    )
            }

            val staleHydration = writer.loadIfCurrent(ACCOUNT, GROUP, sentGeneration)
            val deletion =
                writer.deleteIfCurrent(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    generation = requireNotNull(cleanupGeneration),
                )
            val clearedHydration =
                writer.loadIfCurrent(ACCOUNT, GROUP, requireNotNull(cleanupGeneration))

            assertNull("a read captured before acceptance must not resurrect sent text", staleHydration)
            assertTrue(deletion is MessageDraftConditionalDeleteResult.Applied)
            assertNull(gateway.current)
            assertNotNull(clearedHydration)
            assertNull(clearedHydration?.getOrThrow())
        }

    @Test
    fun optimisticSendBlocksAuthoritativeHydrationWithoutDeletingRecoveryDraft() =
        runTest {
            val gateway = FakeDraftGateway(draft(content = "sending"))
            val writer = writer(gateway)
            val sentGeneration = writer.generation(ACCOUNT, GROUP)
            var lifecycleProjectionHidden = false

            val claimed =
                writer.beginPendingSendPresentation(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    sentGeneration = sentGeneration,
                    onClaimed = { lifecycleProjectionHidden = true },
                )
            val reentryHydration = writer.loadIfCurrent(ACCOUNT, GROUP, sentGeneration)

            assertTrue(claimed)
            assertTrue(lifecycleProjectionHidden)
            assertNull("pending text must not rehydrate on conversation re-entry", reentryHydration)
            assertEquals("sending", gateway.current?.content)
            assertEquals("blocked re-entry must not cross the MDK boundary", 0, gateway.readCalls)
        }

    @Test
    fun optimisticSendPreventsInFlightHydrationFromCommittingLifecycleProjection() =
        runTest {
            val gateway = FakeDraftGateway(draft(content = "sending"))
            val writer = writer(gateway)
            val sentGeneration = writer.generation(ACCOUNT, GROUP)
            var pendingPresentationClaimed = false
            gateway.onRead = {
                pendingPresentationClaimed =
                    writer.beginPendingSendPresentation(ACCOUNT, GROUP, sentGeneration)
            }

            val inFlightHydration = writer.loadIfCurrent(ACCOUNT, GROUP, sentGeneration)
            var lifecycleProjectionCommitted = false
            inFlightHydration?.onSuccess {
                writer.runHydrationIfCurrent(ACCOUNT, GROUP, sentGeneration) {
                    lifecycleProjectionCommitted = true
                }
            }

            assertTrue(pendingPresentationClaimed)
            assertFalse(lifecycleProjectionCommitted)
            assertEquals("sending", gateway.current?.content)
            assertEquals(1, gateway.readCalls)
        }

    @Test
    fun successfulSendCleanupPreventsTheSentGenerationFromCommittingAProjection() =
        runTest {
            val writer = writer(FakeDraftGateway(draft(content = "sent")))
            val sentGeneration = writer.generation(ACCOUNT, GROUP)
            val cleanupGeneration =
                requireNotNull(writer.beginSuccessfulSendCleanup(ACCOUNT, GROUP, sentGeneration))
            var staleProjectionCommitted = false
            var cleanupProjectionCommitted = false

            val staleWasCurrent =
                writer.runIfCurrent(ACCOUNT, GROUP, sentGeneration) {
                    staleProjectionCommitted = true
                }
            val cleanupWasCurrent =
                writer.runIfCurrent(ACCOUNT, GROUP, cleanupGeneration) {
                    cleanupProjectionCommitted = true
                }

            assertFalse(staleWasCurrent)
            assertFalse(staleProjectionCommitted)
            assertTrue(cleanupWasCurrent)
            assertTrue(cleanupProjectionCommitted)
        }

    @Test
    fun newerDraftSupersedesClaimedSuccessfulSendCleanup() =
        runTest {
            val gateway = FakeDraftGateway(draft(content = "sent"))
            val writer = writer(gateway)
            val sentGeneration = writer.generation(ACCOUNT, GROUP)
            val cleanupGeneration =
                requireNotNull(writer.beginSuccessfulSendCleanup(ACCOUNT, GROUP, sentGeneration))

            writer.submit(ACCOUNT, GROUP, "next draft")
            val deletion = writer.deleteIfCurrent(ACCOUNT, GROUP, cleanupGeneration)
            writer.flush()

            assertEquals(MessageDraftConditionalDeleteResult.Superseded, deletion)
            assertEquals("next draft", gateway.current?.content)
        }

    @Test
    fun failedSuccessfulSendDeletionBlocksHydrationUntilNewTextIsAccepted() =
        runTest {
            val gateway =
                FakeDraftGateway(draft(content = "sent")).apply {
                    throwBeforeNextDelete = true
                }
            val writer = writer(gateway)
            val sentGeneration = writer.generation(ACCOUNT, GROUP)
            val cleanupGeneration =
                requireNotNull(writer.beginSuccessfulSendCleanup(ACCOUNT, GROUP, sentGeneration))

            val deletion = writer.deleteIfCurrent(ACCOUNT, GROUP, cleanupGeneration)
            val blockedHydration = writer.loadIfCurrent(ACCOUNT, GROUP, writer.generation(ACCOUNT, GROUP))

            assertTrue(deletion is MessageDraftConditionalDeleteResult.Applied)
            val deletionResult = (deletion as MessageDraftConditionalDeleteResult.Applied).result
            assertTrue(deletionResult is MessageDraftMutationResult.Failure)
            assertNull("failed cleanup must not restore accepted text", blockedHydration)
            assertEquals("sent", gateway.current?.content)

            writer.submit(ACCOUNT, GROUP, "next draft")
            writer.flush()
            val nextHydration = writer.loadIfCurrent(ACCOUNT, GROUP, writer.generation(ACCOUNT, GROUP))

            assertEquals("next draft", nextHydration?.getOrThrow()?.content)
        }

    @Test
    fun successfulSendCleanupSerializesNewTextAcceptedDuringDeleteAfterDeletion() =
        runTest {
            val gateway = FakeDraftGateway(null)
            val writer = writer(gateway)
            writer.submit(ACCOUNT, GROUP, "sent")
            writer.flush()
            val sentGeneration = writer.generation(ACCOUNT, GROUP)
            gateway.onDelete = { writer.submit(ACCOUNT, GROUP, "next draft") }

            val result = writer.deleteIfCurrent(ACCOUNT, GROUP, sentGeneration)
            writer.flush()

            assertTrue(result is MessageDraftConditionalDeleteResult.Applied)
            assertTrue(
                (result as MessageDraftConditionalDeleteResult.Applied).result is MessageDraftMutationResult.Success,
            )
            assertEquals("next draft", gateway.current?.content)
        }

    @Test
    fun successfulSendCleanupDoesNotDeleteInboundShareAcceptedAfterSend() =
        runTest {
            val gateway = FakeDraftGateway(null)
            val drafts = repository(gateway)
            val writer = writer(drafts)
            writer.submit(ACCOUNT, GROUP, "sent")
            writer.flush()
            val sentGeneration = writer.generation(ACCOUNT, GROUP)

            writer.mergeText(ACCOUNT, GROUP, "shared next")
            val result = writer.deleteIfCurrent(ACCOUNT, GROUP, sentGeneration)

            assertEquals(MessageDraftConditionalDeleteResult.Superseded, result)
            assertEquals("sent\nshared next", gateway.current?.content)
        }

    @Test
    fun successfulSendCleanupDoesNotDeleteAttachmentAcceptedAfterSend() =
        runTest {
            val gateway = FakeDraftGateway(null)
            val drafts = repository(gateway)
            val writer = writer(drafts)
            writer.submit(ACCOUNT, GROUP, "sent")
            writer.flush()
            val sentGeneration = writer.generation(ACCOUNT, GROUP)

            drafts.addAttachment(ACCOUNT, GROUP, attachment("next", byteArrayOf(1)))
            val result = writer.deleteIfCurrent(ACCOUNT, GROUP, sentGeneration)

            assertEquals(MessageDraftConditionalDeleteResult.Superseded, result)
            assertEquals(
                "next",
                gateway.current
                    ?.mediaAttachments
                    ?.single()
                    ?.id,
            )
        }

    @Test
    fun asynchronousHydrationIsDiscardedWhenTextIsAcceptedDuringRead() =
        runTest {
            val gateway = FakeDraftGateway(draft(content = "authoritative"))
            val writer = writer(gateway)
            val generation = writer.generation(ACCOUNT, GROUP)
            gateway.onRead = { writer.submit(ACCOUNT, GROUP, "typed while loading") }

            val result = writer.loadIfCurrent(ACCOUNT, GROUP, generation)
            writer.flush()

            assertNull(result)
            assertEquals("typed while loading", gateway.current?.content)
        }

    @Test
    fun inboundMergePreservesTextAcceptedWhileAuthoritativeMergeIsRunning() =
        runTest {
            val gateway = FakeDraftGateway(draft(content = "existing"))
            val writer = writer(gateway)
            gateway.onRead = { writer.submit(ACCOUNT, GROUP, "typed while sharing") }

            val result = writer.mergeText(ACCOUNT, GROUP, "shared")

            assertTrue(result.result is MessageDraftMutationResult.Success)
            assertEquals("typed while sharing\nshared", gateway.current?.content)
            assertEquals("typed while sharing\nshared", result.contentForHydration)
        }

    /** Verifies merge completion exposes the timestamp of the persisted update rather than creation. */
    @Test
    fun inboundMergeCompletionReportsThePersistedUpdateTime() =
        runTest {
            val gateway = FakeDraftGateway(draft(content = "existing"))
            val writer = writer(gateway)

            val completion = writer.mergeText(ACCOUNT, GROUP, "shared")

            assertEquals(gateway.current?.updatedAtMs, completion.draftedAtMs)
            assertTrue(completion.draftedAtMs != gateway.current?.createdAtMs)
        }

    @Test
    fun failedCatchUpSaveReturnsComposedTextForHydration() =
        runTest {
            val gateway = FakeDraftGateway(draft(content = "existing"))
            val writer = writer(gateway)
            gateway.onSave = { content ->
                if (content == "existing\nshared") {
                    gateway.onSave = {}
                    gateway.throwBeforeNextSave = true
                    writer.submit(ACCOUNT, GROUP, "typed while sharing")
                }
            }

            val result = writer.mergeText(ACCOUNT, GROUP, "shared")

            assertTrue(result.result is MessageDraftMutationResult.Failure)
            assertEquals("existing\nshared", gateway.current?.content)
            assertEquals("typed while sharing\nshared", result.contentForHydration)
        }

    @Test
    fun inboundShareReadFailureIsReturned() =
        runTest {
            val gateway = FakeDraftGateway(null).apply { readFailure = IllegalStateException("read failed") }
            val writer = writer(gateway)

            val result = writer.mergeText(ACCOUNT, GROUP, "shared")

            assertTrue(result.result is MessageDraftMutationResult.Failure)
            assertNull(result.contentForHydration)
        }

    @Test
    fun ambiguousDeleteFailureReturnsConfirmedAuthoritativeDeletion() =
        runTest {
            val gateway = FakeDraftGateway(draft(content = "sent")).apply { throwAfterNextDelete = true }

            val result = repository(gateway).delete(ACCOUNT, GROUP)

            assertTrue(result is MessageDraftMutationResult.Success)
            assertNull(gateway.current)
        }

    @Test
    fun replacementPreservesLatestTextReplyOrderAndOtherAttachments() =
        runTest {
            val original = attachment("target", byteArrayOf(1))
            val other = attachment("other", byteArrayOf(2))
            val replacement = attachment("target", byteArrayOf(3))
            val gateway =
                FakeDraftGateway(
                    draft(content = "latest text", replyTo = "reply", attachments = arrayOf(original, other)),
                )
            val sessions = EditorSessionStore(RepositorySessionStrings())
            val repository = repository(gateway, sessions)

            val result =
                repository.replaceAttachment(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    attachmentId = original.id,
                    expectedDigest = original.editorDigest(),
                    replacement = replacement,
                    pendingSession = session(replacement),
                )

            assertTrue(result is MessageDraftMutationResult.Success)
            assertEquals("latest text", gateway.current?.content)
            assertEquals("reply", gateway.current?.replyToMessageIdHex)
            assertEquals(listOf("target", "other"), gateway.current?.mediaAttachments?.map { it.id })
            assertEquals(
                3,
                gateway.current
                    ?.mediaAttachments
                    ?.first()
                    ?.plaintext
                    ?.single()
                    ?.toInt(),
            )
            assertEquals(1, gateway.saveCalls)
            assertTrue(
                sessions.committed(ACCOUNT, GROUP, "target", replacement.editorDigest()) != null,
            )
        }

    @Test
    fun staleBytesFailClosedWithoutSavingOrReplacingPriorSession() =
        runTest {
            val current = attachment("target", byteArrayOf(2))
            val replacement = attachment("target", byteArrayOf(3))
            val gateway = FakeDraftGateway(draft(attachments = arrayOf(current)))
            val sessions = EditorSessionStore(RepositorySessionStrings())
            val prior = session(current)
            sessions.savePending(prior)
            sessions.promote(ACCOUNT, GROUP, current.id, current.editorDigest())
            val repository = repository(gateway, sessions)

            val result =
                repository.replaceAttachment(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    attachmentId = current.id,
                    expectedDigest = attachment("target", byteArrayOf(1)).editorDigest(),
                    replacement = replacement,
                    pendingSession = session(replacement),
                )

            assertEquals(MessageDraftMutationResult.StaleAttachment, result)
            assertEquals(0, gateway.saveCalls)
            assertEquals(
                prior.sourceLeaseId,
                sessions.committed(ACCOUNT, GROUP, current.id, current.editorDigest())?.sourceLeaseId,
            )
        }

    @Test
    fun missingStableIdDoesNotReplaceAttachmentAtSameIndex() =
        runTest {
            val current = attachment("different-id", byteArrayOf(1))
            val replacement = attachment("target", byteArrayOf(2))
            val gateway = FakeDraftGateway(draft(attachments = arrayOf(current)))
            val repository = repository(gateway)

            val result =
                repository.replaceAttachment(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    attachmentId = replacement.id,
                    expectedDigest = current.editorDigest(),
                    replacement = replacement,
                    pendingSession = session(replacement),
                )

            assertEquals(MessageDraftMutationResult.MissingAttachment, result)
            assertEquals(0, gateway.saveCalls)
        }

    @Test
    fun ambiguousSaveFailureReReadsAndPromotesCommittedAttachment() =
        runTest {
            val original = attachment("target", byteArrayOf(1))
            val replacement = attachment("target", byteArrayOf(2))
            val gateway =
                FakeDraftGateway(draft(attachments = arrayOf(original))).apply {
                    throwAfterNextSave = true
                }
            val sessions = EditorSessionStore(RepositorySessionStrings())
            val repository = repository(gateway, sessions)

            val result =
                repository.replaceAttachment(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    attachmentId = original.id,
                    expectedDigest = original.editorDigest(),
                    replacement = replacement,
                    pendingSession = session(replacement),
                )

            assertTrue(result is MessageDraftMutationResult.Success)
            assertTrue(
                sessions.committed(ACCOUNT, GROUP, "target", replacement.editorDigest()) != null,
            )
        }

    @Test
    fun failedSaveDiscardsPendingButPreservesPriorCommittedSession() =
        runTest {
            val original = attachment("target", byteArrayOf(1))
            val replacement = attachment("target", byteArrayOf(2))
            val gateway =
                FakeDraftGateway(draft(attachments = arrayOf(original))).apply {
                    throwBeforeNextSave = true
                }
            val sessions = EditorSessionStore(RepositorySessionStrings())
            val originalSession = session(original)
            sessions.savePending(originalSession)
            sessions.promote(ACCOUNT, GROUP, original.id, original.editorDigest())
            val repository = repository(gateway, sessions)

            val result =
                repository.replaceAttachment(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    attachmentId = original.id,
                    expectedDigest = original.editorDigest(),
                    replacement = replacement,
                    pendingSession = session(replacement),
                )

            assertTrue(result is MessageDraftMutationResult.Failure)
            assertEquals(
                originalSession.sourceLeaseId,
                sessions.committed(ACCOUNT, GROUP, original.id, original.editorDigest())?.sourceLeaseId,
            )
            assertNull(sessions.committed(ACCOUNT, GROUP, replacement.id, replacement.editorDigest()))
        }

    @Test
    fun textSavePreservesHydratedAttachments() =
        runTest {
            val attachment = attachment("target", byteArrayOf(1))
            val gateway = FakeDraftGateway(draft(content = "old", attachments = arrayOf(attachment)))
            val repository = repository(gateway)

            val result = repository.saveText(ACCOUNT, GROUP, "new")

            assertTrue(result is MessageDraftMutationResult.Success)
            assertEquals("new", gateway.current?.content)
            assertEquals(
                attachment.editorDigest(),
                gateway.current
                    ?.mediaAttachments
                    ?.single()
                    ?.editorDigest(),
            )
        }

    @Test
    fun textSavePreservesReplyAndAmbiguousCommitIsConfirmed() =
        runTest {
            val gateway =
                FakeDraftGateway(draft(content = "old", replyTo = "reply")).apply {
                    throwAfterNextSave = true
                }

            val result = repository(gateway).saveText(ACCOUNT, GROUP, "latest")

            assertTrue(result is MessageDraftMutationResult.Success)
            assertEquals("latest", gateway.current?.content)
            assertEquals("reply", gateway.current?.replyToMessageIdHex)
        }

    @Test
    fun blankTextDeletesOnlyAnOtherwiseEmptyDraft() =
        runTest {
            val empty = FakeDraftGateway(draft(content = "old"))
            val withReply = FakeDraftGateway(draft(content = "old", replyTo = "reply"))

            repository(empty).saveText(ACCOUNT, GROUP, " ")
            repository(withReply).saveText(ACCOUNT, GROUP, " ")

            assertNull(empty.current)
            assertEquals("reply", withReply.current?.replyToMessageIdHex)
        }

    @Test
    fun inboundMergeUsesAuthoritativeContentAndPreservesOtherFields() =
        runTest {
            val attachment = attachment("target", byteArrayOf(1))
            val gateway = FakeDraftGateway(draft(content = "existing", replyTo = "reply", attachment))

            val result = repository(gateway).mergeText(ACCOUNT, GROUP, " incoming ")

            assertTrue(result is MessageDraftMutationResult.Success)
            assertEquals("existing\nincoming", gateway.current?.content)
            assertEquals("reply", gateway.current?.replyToMessageIdHex)
            assertEquals(
                "target",
                gateway.current
                    ?.mediaAttachments
                    ?.single()
                    ?.id,
            )
        }

    @Test
    fun removingLastAttachmentDeletesAnOtherwiseEmptyDraftAndSession() =
        runTest {
            val attachment = attachment("target", byteArrayOf(1))
            val gateway = FakeDraftGateway(draft(attachments = arrayOf(attachment)))
            val sessions = EditorSessionStore(RepositorySessionStrings())
            val committed = session(attachment)
            sessions.savePending(committed)
            sessions.promote(ACCOUNT, GROUP, attachment.id, attachment.editorDigest())
            val repository = repository(gateway, sessions)

            val result =
                repository.removeAttachment(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    attachmentId = attachment.id,
                    expectedDigest = attachment.editorDigest(),
                )

            assertTrue(result is MessageDraftMutationResult.Success)
            assertNull(gateway.current)
            assertNull(sessions.committed(ACCOUNT, GROUP, attachment.id, attachment.editorDigest()))
        }

    @Test
    fun removingLastAttachmentPreservesNonblankText() =
        runTest {
            val attachment = attachment("target", byteArrayOf(1))
            val gateway = FakeDraftGateway(draft(content = "caption", attachments = arrayOf(attachment)))
            val repository = repository(gateway)

            val result =
                repository.removeAttachment(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    attachmentId = attachment.id,
                )

            assertTrue(result is MessageDraftMutationResult.Success)
            assertEquals("caption", gateway.current?.content)
            assertTrue(
                gateway.current
                    ?.mediaAttachments
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun startupReconciliationPromotesCommittedPendingAndReclaimsStaleSource() =
        runTest {
            val payloads = RepositoryPayloads()
            val sources =
                EditorSourceStore(
                    payloads = payloads,
                    records = RepositorySessionStrings(),
                    newId = { "lease-${payloads.values.size}" },
                )
            val keepLease = (sources.stageBytes(byteArrayOf(1)) as EditorSourceStageResult.Success).lease
            val staleLease = (sources.stageBytes(byteArrayOf(2)) as EditorSourceStageResult.Success).lease
            val keep = attachment("keep", byteArrayOf(1))
            val stale = attachment("stale", byteArrayOf(2))
            val gateway = FakeDraftGateway(draft(attachments = arrayOf(keep)))
            val sessions = EditorSessionStore(RepositorySessionStrings())
            sessions.savePending(session(keep).copy(sourceLeaseId = keepLease.id))
            sessions.savePending(session(stale).copy(sourceLeaseId = staleLease.id))
            val repository = repository(gateway, sessions)

            val result = repository.reconcileEditorState(sources)

            assertEquals(1, result.getOrThrow())
            assertTrue(sessions.committed(ACCOUNT, GROUP, keep.id, keep.editorDigest()) != null)
            assertTrue(sources.bytes(keepLease.id) != null)
            assertNull(sources.bytes(staleLease.id))
        }

    @Test
    fun startupReconciliationKeepsSourcesWhenEncryptedSessionsCannotBeRead() =
        runTest {
            val payloads = RepositoryPayloads()
            val sources =
                EditorSourceStore(
                    payloads = payloads,
                    records = RepositorySessionStrings(),
                    newId = { "lease" },
                )
            val lease = (sources.stageBytes(byteArrayOf(1)) as EditorSourceStageResult.Success).lease
            val sessionRecords = RepositorySessionStrings(failReads = true)

            val result =
                repository(
                    gateway = FakeDraftGateway(null),
                    sessions = EditorSessionStore(sessionRecords),
                ).reconcileEditorState(sources)

            assertTrue(result.isFailure)
            assertTrue(sources.bytes(lease.id) != null)
        }

    @Test
    fun startupReconciliationFailsWhenEncryptedSourcesCannotBeRead() =
        runTest {
            val payloads = RepositoryPayloads()
            val sourceRecords = RepositorySessionStrings()
            val initialSources =
                EditorSourceStore(
                    payloads = payloads,
                    records = sourceRecords,
                    newId = { "lease" },
                )
            val lease = (initialSources.stageBytes(byteArrayOf(1)) as EditorSourceStageResult.Success).lease
            sourceRecords.failReads = true
            val unavailableSources =
                EditorSourceStore(
                    payloads = payloads,
                    records = sourceRecords,
                )

            val result =
                repository(
                    gateway = FakeDraftGateway(null),
                    sessions = EditorSessionStore(RepositorySessionStrings()),
                ).reconcileEditorState(unavailableSources)

            assertTrue(result.isFailure)
            assertEquals(
                "Encrypted editor sources are temporarily unavailable",
                result.exceptionOrNull()?.message,
            )
            assertTrue(payloads.values.containsKey(lease.id))
        }

    @Test
    fun draftReadRethrowsCancellation() =
        runTest {
            val gateway = FakeDraftGateway(null).apply { readFailure = CancellationException("cancelled") }

            var propagated = false
            try {
                repository(gateway).draft(ACCOUNT, GROUP)
            } catch (_: CancellationException) {
                propagated = true
            }

            assertTrue(propagated)
        }

    @Test
    fun editorReconciliationRethrowsCancellation() =
        runTest {
            val sessions = EditorSessionStore(RepositorySessionStrings())
            val existing = attachment("target", byteArrayOf(1))
            assertTrue(sessions.savePending(session(existing)))
            val gateway = FakeDraftGateway(null).apply { readFailure = CancellationException("cancelled") }
            val sources =
                EditorSourceStore(
                    payloads = RepositoryPayloads(),
                    records = RepositorySessionStrings(),
                )

            var propagated = false
            try {
                repository(gateway, sessions).reconcileEditorState(sources)
            } catch (_: CancellationException) {
                propagated = true
            }

            assertTrue(propagated)
        }

    private fun repository(
        gateway: FakeDraftGateway,
        sessions: EditorSessionStore = EditorSessionStore(RepositorySessionStrings()),
    ) = MessageDraftRepository(
        gateway = gateway,
        editorSessions = sessions,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun CoroutineScope.writer(gateway: FakeDraftGateway) = writer(repository(gateway))

    private fun CoroutineScope.writer(drafts: MessageDraftRepository) =
        CoalescingMessageDraftWriter(
            scope = this,
            drafts = drafts,
            debounceMillis = 0,
        )

    private fun session(attachment: MessageDraftAttachmentFfi) =
        EditorAttachmentSession(
            accountRef = ACCOUNT,
            groupIdHex = GROUP,
            attachmentId = attachment.id,
            attachmentDigest = attachment.editorDigest(),
            sourceLeaseId = "source-${attachment.plaintext.single()}",
            qualityPreference = "standard",
            recipe = PhotoEditRecipe(),
            phase = EditorSessionPhase.Pending,
            updatedAtMs = 0,
        )

    private fun attachment(
        id: String,
        bytes: ByteArray,
    ) = MessageDraftAttachmentFfi(
        id = id,
        fileName = "$id.jpg",
        mediaType = "image/jpeg",
        plaintext = bytes,
        dim = "1x1",
        thumbhash = "hash",
        durationSeconds = null,
        waveformSamples = emptyList(),
    )

    private fun draft(
        content: String = "",
        replyTo: String? = null,
        vararg attachments: MessageDraftAttachmentFfi,
    ) = MessageDraftFfi(
        groupIdHex = GROUP,
        content = content,
        replyToMessageIdHex = replyTo,
        mediaAttachments = attachments.toList(),
        createdAtMs = 1,
        updatedAtMs = 2,
    )

    companion object {
        private const val ACCOUNT = "account"
        private const val GROUP = "group"
    }
}

private class FakeDraftGateway(
    var current: MessageDraftFfi?,
) : MessageDraftGateway {
    var readCalls = 0
    var saveCalls = 0
    var readFailure: Throwable? = null
    var throwBeforeNextSave = false
    var throwAfterNextSave = false
    var throwBeforeNextDelete = false
    var throwAfterNextDelete = false
    var onSave: (String) -> Unit = {}
    var onRead: () -> Unit = {}
    var onDelete: () -> Unit = {}

    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? {
        readCalls += 1
        readFailure?.let { throw it }
        val callback = onRead
        onRead = {}
        callback()
        return current
    }

    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi {
        saveCalls += 1
        if (throwBeforeNextSave) {
            throwBeforeNextSave = false
            error("save failed before commit")
        }
        val saved =
            MessageDraftFfi(
                groupIdHex = groupIdHex,
                content = content,
                replyToMessageIdHex = replyToMessageIdHex,
                mediaAttachments = mediaAttachments,
                createdAtMs = current?.createdAtMs ?: 1,
                updatedAtMs = (current?.updatedAtMs ?: 0) + 1,
            )
        current = saved
        onSave(content)
        if (throwAfterNextSave) {
            throwAfterNextSave = false
            error("save failed after commit")
        }
        return saved
    }

    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) {
        if (throwBeforeNextDelete) {
            throwBeforeNextDelete = false
            error("delete failed before commit")
        }
        current = null
        val callback = onDelete
        onDelete = {}
        callback()
        if (throwAfterNextDelete) {
            throwAfterNextDelete = false
            error("delete failed after commit")
        }
    }

    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}

private class RepositorySessionStrings(
    var failReads: Boolean = false,
) : EditorStringStore {
    private var values = linkedMapOf<String, String>()

    override fun readAll(): Map<String, String>? = if (failReads) null else values.toMap()

    override fun replaceAll(values: Map<String, String>): Boolean {
        this.values = LinkedHashMap(values)
        return true
    }

    override fun clear() = values.clear()
}

private class RepositoryPayloads : EditorEncryptedPayloadStore {
    val values = linkedMapOf<String, ByteArray>()

    override fun prepare() = Unit

    override fun contains(key: String): Boolean = key in values

    override fun get(key: String): ByteArray? = values[key]?.copyOf()

    override fun put(
        key: String,
        bytes: ByteArray,
    ): Boolean {
        values[key] = bytes.copyOf()
        return true
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() = values.clear()
}
