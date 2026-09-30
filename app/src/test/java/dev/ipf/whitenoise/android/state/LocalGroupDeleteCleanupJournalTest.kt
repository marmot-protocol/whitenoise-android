package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalGroupDeleteCleanupJournalTest {
    @get:Rule val folder = TemporaryFolder()

    private val pending =
        PendingLocalGroupDeleteCleanup(
            account = "account-a",
            groupIdHex = "group-a",
            mediaCacheKeys = listOf("account-a|group-a|message|0"),
            ciphertextTags = setOf("ciphertext-a"),
        )

    @Test
    fun stagedIntentSurvivesNewJournalAndIsScopedToAccountAndGroup() {
        val directory = folder.newFolder("local-delete")
        val journal = LocalGroupDeleteCleanupJournal(directory)
        val other = pending.copy(account = "account-b")
        assertFalse(journal.hasPending())
        journal.stage(pending)
        journal.stage(other)
        assertTrue(journal.hasPending())

        val restarted = LocalGroupDeleteCleanupJournal(directory)
        assertEquals(setOf(pending, other), restarted.pending().toSet())
        restarted.finish(pending)
        assertEquals(listOf(other), LocalGroupDeleteCleanupJournal(directory).pending())
        restarted.finish(other)
        assertFalse(restarted.hasPending())
    }

    @Test
    fun failedJournalStageCannotAuthorizeNativeMutation() {
        val blockedDirectory = folder.newFile("not-a-directory")
        val journal = LocalGroupDeleteCleanupJournal(blockedDirectory)
        var nativeDeletes = 0
        val result =
            runCatching {
                journal.stage(pending)
                nativeDeletes++
            }
        assertTrue(result.isFailure)
        assertEquals(0, nativeDeletes)
    }

    @Test
    fun mismatchedJournalIdentityCannotAuthorizeCleanup() {
        val directory = folder.newFolder("mismatched-identity")
        val journal = LocalGroupDeleteCleanupJournal(directory)
        journal.stage(pending)
        val file = directory.listFiles().single()
        file.writeText(JSONObject(file.readText()).put("account", "account-b").toString())

        assertTrue(journal.pending().isEmpty())
        assertTrue(file.exists())
    }

    @Test
    fun failedReadAfterRestartNeverClearsClientDataOrRepeatsDelete() =
        runTest {
            val directory = folder.newFolder("lost-response")
            LocalGroupDeleteCleanupJournal(directory).stage(pending)
            val restarted = LocalGroupDeleteCleanupJournal(directory)
            var cleanupCalls = 0
            var nativeReads = 0
            val deferred =
                reconcilePendingLocalGroupDeleteCleanup(
                    pending = restarted.pending().single(),
                    accountReady = { true },
                    isGroupPresent = {
                        nativeReads++
                        throw IllegalStateException("transport closed")
                    },
                    cleanup = {
                        cleanupCalls++
                        true
                    },
                    finish = restarted::finish,
                )
            assertFalse(deferred)
            assertEquals(1, nativeReads)
            assertEquals(0, cleanupCalls)
            assertEquals(listOf(pending), restarted.pending())

            val recovered =
                reconcilePendingLocalGroupDeleteCleanup(
                    pending = restarted.pending().single(),
                    accountReady = { true },
                    isGroupPresent = { false },
                    cleanup = {
                        cleanupCalls++
                        true
                    },
                    finish = restarted::finish,
                )
            assertTrue(recovered)
            assertEquals(1, cleanupCalls)
            assertTrue(restarted.pending().isEmpty())
        }

    @Test
    fun presentGroupDropsIntentWithoutClearingData() =
        runTest {
            val directory = folder.newFolder("present")
            val journal = LocalGroupDeleteCleanupJournal(directory)
            journal.stage(pending)
            val result =
                reconcilePendingLocalGroupDeleteCleanup(
                    pending = pending,
                    accountReady = { true },
                    isGroupPresent = { true },
                    cleanup = { error("present group must never be cleaned") },
                    finish = journal::finish,
                )
            assertTrue(result)
            assertTrue(journal.pending().isEmpty())
        }

    @Test
    fun signedOutAccountAndFailedCleanupRetainIntent() =
        runTest {
            val directory = folder.newFolder("deferred")
            val journal = LocalGroupDeleteCleanupJournal(directory)
            journal.stage(pending)
            val signedOut =
                reconcilePendingLocalGroupDeleteCleanup(
                    pending = pending,
                    accountReady = { false },
                    isGroupPresent = { error("signed-out account must not be queried") },
                    cleanup = { error("signed-out account must not be cleaned") },
                    finish = journal::finish,
                )
            assertFalse(signedOut)
            val failedCleanup =
                reconcilePendingLocalGroupDeleteCleanup(
                    pending = pending,
                    accountReady = { true },
                    isGroupPresent = { false },
                    cleanup = { false },
                    finish = journal::finish,
                )
            assertFalse(failedCleanup)
            assertEquals(listOf(pending), journal.pending())
            assertTrue(
                reconcilePendingLocalGroupDeleteCleanup(
                    pending = pending,
                    accountReady = { true },
                    isGroupPresent = { false },
                    cleanup = { true },
                    finish = journal::finish,
                ),
            )
            assertTrue(journal.pending().isEmpty())
        }

    @Test(expected = CancellationException::class)
    fun cancelledNativeReadDoesNotConsumeIntent() =
        runTest {
            val directory = folder.newFolder("cancelled")
            val journal = LocalGroupDeleteCleanupJournal(directory)
            journal.stage(pending)
            try {
                reconcilePendingLocalGroupDeleteCleanup(
                    pending = pending,
                    accountReady = { true },
                    isGroupPresent = { throw CancellationException("account switched") },
                    cleanup = { error("cancelled read must not clean") },
                    finish = journal::finish,
                )
            } finally {
                assertEquals(listOf(pending), journal.pending())
            }
        }
}
