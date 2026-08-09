package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageDraftRepositoryTest {
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

    private fun repository(
        gateway: FakeDraftGateway,
        sessions: EditorSessionStore = EditorSessionStore(RepositorySessionStrings()),
    ) = MessageDraftRepository(
        gateway = gateway,
        editorSessions = sessions,
        ioDispatcher = UnconfinedTestDispatcher(),
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
    var saveCalls = 0
    var throwBeforeNextSave = false
    var throwAfterNextSave = false

    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = current

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
        current = null
    }
}

private class RepositorySessionStrings : EditorStringStore {
    private var values = linkedMapOf<String, String>()

    override fun readAll(): Map<String, String> = values.toMap()

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
