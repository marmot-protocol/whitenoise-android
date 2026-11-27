package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the draft layer reads a chat MarmotKit no longer knows. A local removal deletes the chat's draft with
 * the chat, and the selected read then answers `UnknownGroup`. A plain read reports that as no draft, so one removed
 * chat cannot fail work that spans every chat. Writes and other native failures keep their error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovedChatDraftReadTest {
    /** A plain read of a removed chat reports no draft instead of failing. */
    @Test
    fun readReportsNoDraftForARemovedChat() =
        runTest {
            val native = FileBackedDraftNative("left behind").apply { removedGroups += REMOVED }

            assertNull(MarmotMessageDraftGateway { native.marmot }.read(ACCOUNT, REMOVED))
            assertNull(repositoryFor(native).draft(ACCOUNT, REMOVED).getOrThrow())
        }

    /** A native failure that is not a removed chat still fails the read, so it is never mistaken for no draft. */
    @Test
    fun readStillFailsWhenTheNativeReadFails() {
        val failure = MarmotKitException.TransportClosed()
        val native = FileBackedDraftNative("kept").apply { selectedReadFailure = failure }

        val thrown =
            assertThrows(MarmotKitException.TransportClosed::class.java) {
                MarmotMessageDraftGateway { native.marmot }.read(ACCOUNT, LIVE)
            }

        assertSame(failure, thrown)
    }

    /** A write to a removed chat still fails with the native error and never reaches a native write. */
    @Test
    fun saveToARemovedChatStillFails() =
        runTest {
            val native = FileBackedDraftNative().apply { removedGroups += REMOVED }

            val result = repositoryFor(native).saveText(ACCOUNT, REMOVED, "hello")

            val cause = (result as? MessageDraftMutationResult.Failure)?.cause
            assertTrue("expected the native refusal, got $result", cause is MarmotKitException.UnknownGroup)
            assertEquals(setOf("selectedMessageDraft"), native.calls.toSet())
        }

    /** Deleting the draft of a removed chat counts as done, because the removal already deleted it. */
    @Test
    fun deleteCountsAsDoneForARemovedChat() =
        runTest {
            val native = FileBackedDraftNative().apply { removedGroups += REMOVED }

            val result = repositoryFor(native).delete(ACCOUNT, REMOVED)

            assertEquals(MessageDraftMutationResult.Success(draft = null), result)
            assertEquals(setOf("selectedMessageDraft"), native.calls.toSet())
        }

    /**
     * Startup reconciliation with an editor session left by a removed chat still settles the live chat's session,
     * drops the removed chat's session and reclaims its source.
     */
    @Test
    fun startupReconciliationSurvivesARemovedChat() =
        runTest {
            val native = FileBackedDraftNative().apply { removedGroups += REMOVED }
            native.holdInline("kept", byteArrayOf(1))
            var leases = 0
            val sources =
                EditorSourceStore(InMemoryEditorPayloads(), InMemoryEditorStrings(), newId = { "lease-${leases++}" })
            val keptLease = (sources.stageBytes(byteArrayOf(1)) as EditorSourceStageResult.Success).lease
            val orphanLease = (sources.stageBytes(byteArrayOf(2)) as EditorSourceStageResult.Success).lease
            val keptDigest =
                MarmotMessageDraftGateway { native.marmot }
                    .read(ACCOUNT, LIVE)!!
                    .mediaAttachments
                    .single()
                    .editorDigest()
            val sessions = EditorSessionStore(InMemoryEditorStrings())
            assertTrue(sessions.savePending(session(LIVE, "kept", keptDigest, keptLease.id)))
            assertTrue(sessions.savePending(session(REMOVED, "orphan", "digest", orphanLease.id)))

            val result = repositoryFor(native, sessions).reconcileEditorState(sources)

            assertEquals(1, result.getOrThrow())
            assertNotNull(sessions.committed(ACCOUNT, LIVE, "kept", keptDigest))
            assertFalse(sessions.attachmentKeys()!!.any { it.second == REMOVED })
            assertNotNull(sources.bytes(keptLease.id))
            assertNull(sources.bytes(orphanLease.id))
        }

    /** A repository over [native] and [sessions] whose I/O runs on the test scheduler. */
    private fun TestScope.repositoryFor(
        native: FileBackedDraftNative,
        sessions: EditorSessionStore = EditorSessionStore(InMemoryEditorStrings()),
    ) = MessageDraftRepository(
        gateway = MarmotMessageDraftGateway { native.marmot },
        editorSessions = sessions,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    /** A pending editor session for one attachment of [groupIdHex]. */
    private fun session(
        groupIdHex: String,
        attachmentId: String,
        digest: String,
        leaseId: String,
    ) = EditorAttachmentSession(
        accountRef = ACCOUNT,
        groupIdHex = groupIdHex,
        attachmentId = attachmentId,
        attachmentDigest = digest,
        sourceLeaseId = leaseId,
        qualityPreference = "standard",
        recipe = PhotoEditRecipe(),
        phase = EditorSessionPhase.Pending,
        updatedAtMs = 0,
    )

    private companion object {
        const val ACCOUNT = "acct"
        const val LIVE = "live"
        const val REMOVED = "removed"
    }
}
