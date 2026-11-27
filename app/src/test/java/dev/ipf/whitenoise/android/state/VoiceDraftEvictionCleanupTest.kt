package dev.ipf.whitenoise.android.state

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

/** A persisted eviction retries independently of the origin account's group and notification subscriptions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceDraftEvictionCleanupTest {
    @get:Rule val folder = TemporaryFolder()
    private val chat = VoiceNoteKey("evicted-account", "ab".repeat(32))

    /** After a blocked purge, a fresh process reads the recorded target and purges it without another update. */
    @Test
    fun failedPurgeSurvivesRestartAndRetriesWithoutAnotherEvent() =
        runTest {
            val store = VoiceDraftStore(folder.newFolder("voice"))
            val journalRoot = folder.newFolder("evictions")
            val journal = VoiceDraftEvictionJournal(journalRoot)
            val directory = store.recordingDirectory(chat)
            val recording = directory.resolve("voice-evicted.m4a").apply { writeText("audio") }
            val draft = checkNotNull(store.commit(chat, recording, 2_000L, stopReason = null))
            val originalScope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val original = VoiceDraftEvictionCleanup(journal, originalScope, { true }, store::purge)
            assertTrue(directory.setWritable(false))
            try {
                original.stage(chat)
                assertFalse(store.purge(chat))
                original.resume()
                runCurrent()

                assertTrue(draft.file.isFile)
                assertEquals(setOf(chat), journal.pending())
            } finally {
                originalScope.cancel()
                assertTrue(directory.setWritable(true))
            }

            val rechecks = mutableListOf<VoiceNoteKey>()
            val restartedJournal = VoiceDraftEvictionJournal(journalRoot)
            val restarted =
                VoiceDraftEvictionCleanup(
                    restartedJournal,
                    backgroundScope,
                    isEvicted = { key ->
                        rechecks += key
                        true
                    },
                    purge = store::purge,
                )
            restarted.resume()
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(listOf(chat), rechecks)
            assertFalse(directory.exists())
            assertTrue(restartedJournal.pending().isEmpty())
        }

    /** An old journal entry cannot purge a chat that native membership says has been rejoined. */
    @Test
    fun replayClearsTheRequestWithoutPurgingARejoinedChat() =
        runTest {
            val journal = VoiceDraftEvictionJournal(folder.newFolder("evictions"))
            assertTrue(journal.stage(chat))
            val cleanup =
                VoiceDraftEvictionCleanup(
                    journal,
                    backgroundScope,
                    isEvicted = { false },
                    purge = { error("active chat must never be purged") },
                )

            cleanup.resume()
            advanceTimeBy(1_000L)
            runCurrent()

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
            val cleanup =
                VoiceDraftEvictionCleanup(
                    journal,
                    backgroundScope,
                    isEvicted = { if (reads++ == 0) oldRead.await() else true },
                    purge = {
                        purges++
                        true
                    },
                )
            cleanup.stage(chat)
            cleanup.resume()
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(1, reads)

            cleanup.stage(chat)
            oldRead.complete(false)
            runCurrent()
            assertEquals(setOf(chat), journal.pending())
            assertEquals(0, purges)

            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(1, purges)
            assertTrue(journal.pending().isEmpty())
        }
}
