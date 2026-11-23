package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A chat's unsent take survives a restart, never leaks identifiers into paths, and is deleted only on purpose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceDraftStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = VoiceNoteKey("account-label", "ab".repeat(32))

    @Test
    fun committedTakeIsRenamedAndRecoveredAfterRestart() {
        val store = VoiceDraftStore(folder.root)
        val recorded = recording(store, "voice-1.m4a")
        val draft = checkNotNull(store.commit(key, recorded, 2_500L, VoiceRecordingStopReason.LimitReached))
        assertFalse(recorded.exists())
        assertTrue(draft.file.name.startsWith("take-"))
        val recovered = VoiceDraftStore(folder.root).recover(key)
        assertEquals(VoiceDraftRecovery.Ready(draft), recovered)
        val acknowledged = store.withStopReason(key, draft, null)
        assertEquals(VoiceDraftRecovery.Ready(acknowledged), VoiceDraftStore(folder.root).recover(key))
    }

    @Test
    fun finalizedDraftIsRecoverableWhenIndexPublicationFails() {
        val store = VoiceDraftStore(folder.root)
        val dir = store.recordingDirectory(key)
        val index = File(dir, "take.json")
        assertTrue(index.mkdir())
        val draft = checkNotNull(store.commit(key, recording(store, "voice-9.m4a"), 3_000L, null))
        assertTrue(draft.file.isFile)
        assertEquals(setOf(store.chatName(key)), store.persistedDrafts())
        assertTrue(index.delete())
        val restarted = VoiceDraftStore(folder.root)
        assertEquals(VoiceDraftRecovery.Ready(draft), restarted.recover(key))
        assertTrue(restarted.delete(key, draft.file))
        assertEquals(VoiceDraftRecovery.None, VoiceDraftStore(folder.root).recover(key))
    }

    @Test
    fun interruptedConsumptionHidesAnUnpublishedTake() {
        val store = VoiceDraftStore(folder.root)
        val dir = store.recordingDirectory(key)
        assertTrue(File(dir, "take.json").mkdir())
        val draft = checkNotNull(store.commit(key, recording(store, "voice-10.m4a"), 3_000L, null))
        // Reopen the exact disk state left if the process stopped before cleanup.
        assertTrue(File(dir, "take.consumed").createNewFile())
        val restarted = VoiceDraftStore(folder.root)
        assertTrue(restarted.persistedDrafts().isEmpty())
        assertEquals(VoiceDraftRecovery.None, restarted.recover(key))
        assertFalse(draft.file.exists())
        assertEquals(VoiceDraftRecovery.None, VoiceDraftStore(folder.root).recover(key))
    }

    @Test
    fun aTakeCutOffByProcessDeathIsReportedLostOnce() {
        val store = VoiceDraftStore(folder.root)
        val unfinished = recording(store, "voice-2.m4a")
        assertEquals(VoiceDraftRecovery.Lost, store.recover(key))
        assertFalse(unfinished.exists())
        assertEquals(VoiceDraftRecovery.None, store.recover(key))
    }

    @Test
    fun aStaleDeleteCannotRemoveANewerTake() {
        val store = VoiceDraftStore(folder.root)
        val first = checkNotNull(store.commit(key, recording(store, "voice-3.m4a"), 1_000L, null))
        val second = checkNotNull(store.commit(key, recording(store, "voice-4.m4a"), 2_000L, null))
        assertFalse(first.file.exists())
        assertFalse(store.delete(key, first.file))
        assertEquals(VoiceDraftRecovery.Ready(second), store.recover(key))
        assertTrue(store.delete(key, second.file))
        assertEquals(VoiceDraftRecovery.None, store.recover(key))
    }

    @Test
    fun accountAndChatPurgesStayInTheirScope() {
        val store = VoiceDraftStore(folder.root)
        val other = VoiceNoteKey("other-account", key.groupIdHex)
        val sibling = VoiceNoteKey(key.accountRef, "cd".repeat(32))
        store.commit(key, recording(store, "voice-5.m4a"), 1_000L, null)
        val kept = checkNotNull(store.commit(other, recording(store, "voice-6.m4a", other), 1_000L, null))
        store.commit(sibling, recording(store, "voice-7.m4a", sibling), 1_000L, null)
        store.purge(sibling)
        assertEquals(VoiceDraftRecovery.None, store.recover(sibling))
        assertTrue(store.recover(key) is VoiceDraftRecovery.Ready)
        store.purgeAccount(key.accountRef)
        assertEquals(VoiceDraftRecovery.None, store.recover(key))
        assertEquals(VoiceDraftRecovery.Ready(kept), store.recover(other))
    }

    @Test
    fun pathsAreHashedAndTheTempSweepNeverMatchesADraft() {
        val store = VoiceDraftStore(folder.root)
        val draft = checkNotNull(store.commit(key, recording(store, "voice-8.m4a"), 1_000L, null))
        val path = draft.file.relativeTo(folder.root).path
        assertFalse(path.contains(key.accountRef) || path.contains(key.groupIdHex))
        draft.file.setLastModified(1L)
        VoiceRecordingController.sweepStaleVoiceTempFiles(draft.file.parentFile!!, nowMillis = Long.MAX_VALUE / 2)
        assertTrue(draft.file.exists())
    }

    private fun recording(
        store: VoiceDraftStore,
        name: String,
        scope: VoiceNoteKey = key,
    ): File = File(store.recordingDirectory(scope), name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
}
