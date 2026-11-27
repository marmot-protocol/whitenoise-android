package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.VoiceDraftRecovery
import dev.ipf.whitenoise.android.audio.VoiceDraftStore
import dev.ipf.whitenoise.android.audio.VoiceNoteKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

/** A persisted eviction retries independently of the origin account's group and notification subscriptions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceDraftEvictionCleanupTest {
    @get:Rule val folder = TemporaryFolder()
    private val chat = VoiceNoteKey("evicted-account", "ab".repeat(32))

    /** A rejoined chat never restores the evicted take, and cleanup after restart leaves a new take alone. */
    @Test
    fun blockedEvictionSurvivesRejoinAndRestartsWithoutDeletingTheReplacement() =
        runTest {
            val draftRoot = folder.newFolder("voice")
            val journalRoot = folder.newFolder("evictions")
            val journal = VoiceDraftEvictionJournal(journalRoot)
            val store = VoiceDraftStore(draftRoot)
            val directory = store.recordingDirectory(chat)
            val accountDirectory = checkNotNull(directory.parentFile)
            val draft = saveTake(store, "evicted")
            val originalScope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val original = VoiceDraftEvictionCleanup(journal, draftRoot, originalScope, { true }, {})
            var retiredDirectory = directory
            assertTrue(directory.setWritable(false))
            try {
                assertTrue(accountDirectory.setWritable(false))
                original.stage(chat, confirmed = true)
                assertFalse(store.purge(chat))
                original.isolate(chat)
                assertTrue(draft.file.isFile)
                assertEquals(mapOf(chat to true), journal.pending())
                originalScope.cancel()

                val restartedScope = CoroutineScope(StandardTestDispatcher(testScheduler))
                val restarted = rejoinedCleanup(journalRoot, draftRoot, restartedScope)
                assertEquals(VoiceDraftRecovery.None, restarted.draftStore.recover(chat))
                assertTrue(restarted.draftStore.persistedDrafts().isEmpty())
                assertFalse(restarted.draftStore.canRecord(chat))
                assertTrue(accountDirectory.setWritable(true))
                restarted.resume()
                advanceTimeBy(1_000L)
                runCurrent()

                retiredDirectory = checkNotNull(accountDirectory.listFiles()).single()
                assertTrue(retiredDirectory.resolve(draft.file.name).isFile)
                assertEquals(VoiceDraftRecovery.None, restarted.draftStore.recover(chat))
                assertTrue(restarted.draftStore.canRecord(chat))
                assertTrue(journal.pending().isEmpty())

                val replacement = saveTake(restarted.draftStore, "rejoined")
                restartedScope.cancel()
                assertTrue(retiredDirectory.setWritable(true))
                val secondRestart = rejoinedCleanup(journalRoot, draftRoot, backgroundScope)
                secondRestart.resume()
                advanceTimeBy(1_000L)
                runCurrent()

                assertFalse(retiredDirectory.exists())
                assertEquals(VoiceDraftRecovery.Ready(replacement), secondRestart.draftStore.recover(chat))
            } finally {
                originalScope.cancel()
                accountDirectory.setWritable(true)
                retiredDirectory.setWritable(true)
            }
        }

    /** An old journal entry cannot purge a chat that native membership says has been rejoined. */
    @Test
    fun replayClearsTheRequestWithoutPurgingARejoinedChat() =
        runTest {
            val journal = VoiceDraftEvictionJournal(folder.newFolder("evictions"))
            assertTrue(journal.stage(chat, confirmed = false))
            val cleanup =
                VoiceDraftEvictionCleanup(
                    journal,
                    folder.newFolder("voice"),
                    backgroundScope,
                    isEvicted = { false },
                    releaseEvictedTarget = { error("active chat must never be purged") },
                )

            cleanup.resume()
            advanceTimeBy(1_000L)
            runCurrent()

            assertTrue(journal.pending().isEmpty())
        }

    /** A restored readback survives a transient lookup failure and settles without another group update. */
    @Test
    fun failedMembershipRecheckRetriesUntilEvictionIsConfirmed() =
        runTest {
            val journal = VoiceDraftEvictionJournal(folder.newFolder("evictions"))
            assertTrue(journal.stage(chat, confirmed = false))
            var reads = 0
            val released = mutableListOf<VoiceNoteKey>()
            lateinit var cleanup: VoiceDraftEvictionCleanup
            cleanup =
                VoiceDraftEvictionCleanup(
                    journal,
                    folder.newFolder("voice"),
                    backgroundScope,
                    isEvicted = { if (reads++ == 0) null else true },
                    releaseEvictedTarget = {
                        released += it
                        cleanup.complete(it)
                    },
                )
            cleanup.resume()
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(mapOf(chat to false), journal.pending())
            assertTrue(released.isEmpty())

            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(listOf(chat), released)
            assertTrue(journal.pending().isEmpty())
        }

    /** A read started for an earlier eviction cannot settle a newer request for the same chat. */
    @Test
    fun laterEvictionSupersedesAReadOfTheRejoinedChat() =
        runTest {
            val journal = VoiceDraftEvictionJournal(folder.newFolder("evictions"))
            val oldRead = CompletableDeferred<Boolean>()
            var reads = 0
            var purges = 0
            lateinit var cleanup: VoiceDraftEvictionCleanup
            cleanup =
                VoiceDraftEvictionCleanup(
                    journal,
                    folder.newFolder("voice"),
                    backgroundScope,
                    isEvicted = { if (reads++ == 0) oldRead.await() else true },
                    releaseEvictedTarget = {
                        purges++
                        cleanup.complete(it)
                    },
                )
            cleanup.stage(chat, confirmed = false)
            cleanup.resume()
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(1, reads)

            cleanup.stage(chat, confirmed = false)
            oldRead.complete(false)
            runCurrent()
            assertEquals(mapOf(chat to false), journal.pending())
            assertEquals(0, purges)

            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(1, purges)
            assertTrue(journal.pending().isEmpty())
        }

    /** A confirmed eviction has no need to re-read membership or revoke a rejoined chat's owners. */
    private fun rejoinedCleanup(
        journalRoot: File,
        draftRoot: File,
        scope: CoroutineScope,
    ): VoiceDraftEvictionCleanup =
        VoiceDraftEvictionCleanup(
            VoiceDraftEvictionJournal(journalRoot),
            draftRoot,
            scope,
            isEvicted = { error("confirmed eviction does not depend on later membership") },
            releaseEvictedTarget = { error("retry must leave the rejoined chat's capture alone") },
        )

    /** Saves a take at the same path a real recorder would use. */
    private fun saveTake(
        store: VoiceDraftStore,
        name: String,
    ) = checkNotNull(
        store.commit(
            chat,
            store.recordingDirectory(chat).resolve("voice-$name.m4a").apply { writeText(name) },
            durationMs = 2_000L,
            stopReason = null,
        ),
    )
}
