package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises real coroutine cancellation overlapping a same-ID Android job restart. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AttachmentDownloadJobRunsTest {
    @Test
    fun cancelledFetchFinishesItsStillActiveAndroidJob() =
        runTest {
            val runs = AttachmentDownloadJobRuns(backgroundScope)
            var finishes = 0
            val run =
                runs.start(
                    jobId = 1,
                    download = { CompletableDeferred<Unit>().apply { cancel() }.await() },
                    onFinished = { finishes += 1 },
                )

            runCurrent()

            assertTrue(run.isCompleted)
            assertFalse(run.isCancelled)
            assertEquals("the active Android job must end so its notification is removed", 1, finishes)
            assertFalse(runs.stop(1))
        }

    @Test
    fun fetchTimeoutFinishesItsStillActiveAndroidJob() =
        runTest {
            val runs = AttachmentDownloadJobRuns(backgroundScope)
            var finishes = 0
            val run = runs.start(1, download = { withTimeout(10) { delay(20) } }, onFinished = { finishes += 1 })
            runCurrent()
            advanceTimeBy(10)
            runCurrent()

            assertTrue(run.isCompleted)
            assertFalse(run.isCancelled)
            assertEquals(1, finishes)
            assertFalse(runs.stop(1))
        }

    @Test
    fun stoppedRunUnwindingCannotUnregisterItsReplacement() =
        runTest {
            val runs = AttachmentDownloadJobRuns(backgroundScope)
            val releaseOldRun = CompletableDeferred<Unit>()
            var finishes = 0
            val old =
                runs.start(
                    jobId = 1,
                    download = { withContext(NonCancellable) { releaseOldRun.await() } },
                    onFinished = { finishes += 1 },
                )
            runCurrent()
            assertTrue(runs.stop(1))
            val replacement =
                runs.start(1, download = { awaitCancellation() }, onFinished = { finishes += 1 })
            runCurrent()

            releaseOldRun.complete(Unit)
            runCurrent()

            assertTrue(old.isCompleted)
            assertFalse(replacement.isCancelled)
            assertTrue("the restarted download must still be cancellable", runs.stop(1))
            runCurrent()
            assertTrue(replacement.isCancelled)
            assertEquals("neither stopped run may clear intent or finish the Android job", 0, finishes)
        }

    @Test
    fun replacementCancelsOldOwnerAndOnlyCurrentCompletionFinishes() =
        runTest {
            val runs = AttachmentDownloadJobRuns(backgroundScope)
            val releaseOldRun = CompletableDeferred<Unit>()
            val releaseReplacement = CompletableDeferred<Unit>()
            val finishes = mutableListOf<String>()
            val old =
                runs.start(
                    1,
                    download = {
                        withContext(NonCancellable) {
                            releaseOldRun.await()
                            throw CancellationException("replaced fetch retired")
                        }
                    },
                    onFinished = { finishes += "old" },
                )
            runCurrent()
            runs.start(1, download = { releaseReplacement.await() }, onFinished = { finishes += "replacement" })
            runCurrent()

            releaseOldRun.complete(Unit)
            runCurrent()
            assertTrue(old.isCancelled)
            assertTrue(finishes.isEmpty())
            releaseReplacement.complete(Unit)
            runCurrent()

            assertEquals(listOf("replacement"), finishes)
            assertFalse("a completed run must release its tracking entry", runs.stop(1))
        }

    @Test
    fun stoppingOneAttachmentPreservesAnotherAndCancelBeforeStartDoesNotFinish() =
        runTest {
            val runs = AttachmentDownloadJobRuns(backgroundScope)
            var finishes = 0
            val stopped = runs.start(1, download = {}, onFinished = { finishes += 1 })
            val other = runs.start(2, download = { awaitCancellation() }, onFinished = { finishes += 1 })

            assertTrue(runs.stop(1))
            runCurrent()

            assertTrue(stopped.isCancelled)
            assertFalse(other.isCancelled)
            assertFalse(runs.stop(1))
            assertTrue(runs.stop(2))
            runCurrent()
            assertEquals(0, finishes)
        }
}
