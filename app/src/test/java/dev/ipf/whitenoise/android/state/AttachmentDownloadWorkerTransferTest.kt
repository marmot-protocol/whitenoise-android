package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The WorkManager fallback counts its transfer card while it runs and reports how each run ended. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = WhiteNoiseApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentDownloadWorkerTransferTest {
    private lateinit var appContext: Context
    private val lines = mutableListOf<String>()
    private val liveCounts = mutableListOf<Int>()
    private val ledger = AttachmentTransferLedger(onLiveCountChanged = { liveCounts += it }, log = { lines += it })

    /** Starts from a clean work manager and routes the worker's ledger to this test's recording one. */
    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<WhiteNoiseApplication>()
        appContext = application.applicationContext
        WorkManagerTestInitHelper.initializeTestWorkManager(application)
        AttachmentTransfers.installForTest(ledger)
    }

    /** Restores the inert ledger so later tests never see this one. */
    @After
    fun tearDown() {
        AttachmentTransfers.installForTest(null)
    }

    /** The endings recorded so far as outcome labels, in order. */
    private fun outcomes() = lines.endOutcomes()

    /** An explicit transfer is live in the ledger while it downloads, then released as completed. */
    @Test
    fun anInteractiveTransferIsCountedWhileItRunsAndReleasedOnSuccess() =
        runTest {
            markInteractive()
            var liveWhileDownloading = -1

            val result =
                worker(runAttemptCount = 0) { _, _, _ ->
                    liveWhileDownloading = ledger.liveCount()
                    true
                }.doWork()

            assertEquals(Result.success(), result)
            assertEquals(1, liveWhileDownloading)
            assertEquals(0, ledger.liveCount())
            assertEquals(listOf("completed"), outcomes())
            assertEquals("attachment_transfer begin seq=1 owner=work_manager attempt=0", lines.first())
        }

    /** Automatic transfers stay ordinary background work with no card, so they never touch the count. */
    @Test
    fun anAutomaticTransferIsNeverCounted() =
        runTest {
            var liveWhileDownloading = -1

            val result =
                worker(runAttemptCount = 0) { _, _, _ ->
                    liveWhileDownloading = ledger.liveCount()
                    true
                }.doWork()

            assertEquals(Result.success(), result)
            assertEquals(0, liveWhileDownloading)
            assertTrue(lines.isEmpty())
            assertTrue(liveCounts.isEmpty())
        }

    /** A transient failure ends the first run as retrying and the follow-up as failed, never the reverse. */
    @Test
    fun aRetryIsReportedAsRetryingAndTheFollowUpAsFailed() =
        runTest {
            markInteractive()
            val download: PerformDurableAttachmentDownload = { _, _, _ -> throw java.io.IOException("synthetic") }

            assertEquals(Result.retry(), worker(0, request, download).doWork())
            assertEquals(0, ledger.liveCount())
            assertEquals(Result.failure(), worker(1, request, download).doWork())

            assertEquals(0, ledger.liveCount())
            assertEquals(listOf("retrying", "failed"), outcomes())
            assertEquals(listOf("0", "1"), lines.beginAttempts())
        }

    /** A body that is not retained ends the run as failed without leaving a live entry. */
    @Test
    fun aBodyThatWasNotRetainedIsReportedAsFailed() =
        runTest {
            markInteractive()

            assertEquals(Result.failure(), worker(runAttemptCount = 0) { _, _, _ -> false }.doWork())

            assertEquals(0, ledger.liveCount())
            assertEquals(listOf("failed"), outcomes())
        }

    /** A scheduler stop cancels the run, which still ends its entry as stopped and keeps the user's intent. */
    @Test
    fun aStoppedRunReleasesItsEntryAsStopped() =
        runTest {
            val intents = markInteractive()
            val entered = CompletableDeferred<Unit>()
            val run =
                async {
                    worker(runAttemptCount = 2) { _, _, _ ->
                        entered.complete(Unit)
                        awaitCancellation()
                    }.doWork()
                }
            runCurrent()
            entered.await()
            assertEquals(1, ledger.liveCount())

            run.cancel(CancellationException("fixture://private-stop-context"))
            runCurrent()

            assertTrue(run.isCancelled)
            assertEquals(0, ledger.liveCount())
            assertEquals(listOf("stopped"), outcomes())
            assertTrue(intents.isInteractive(request))
        }

    /**
     * Backlog stop, interruption backoff, manual retry: the platform stops the run, the intent survives, and a
     * later explicit retry is a new run with its own entry that completes, so no earlier stop can hide it.
     */
    @Test
    fun aManualRetryAfterABacklogStopIsANewCountedRun() =
        runTest {
            val intents = markInteractive()
            val entered = CompletableDeferred<Unit>()
            val stopped =
                async {
                    worker(runAttemptCount = 40) { _, _, _ ->
                        entered.complete(Unit)
                        awaitCancellation()
                    }.doWork()
                }
            runCurrent()
            entered.await()
            stopped.cancel(CancellationException("backlog"))
            runCurrent()
            assertEquals(0, ledger.liveCount())
            assertTrue("the stop must keep the reader's request", intents.isInteractive(request))
            assertFalse("a stop is not a failure", intents.hasSpentTransientRetry(request))

            var liveDuringRetry = -1
            val retry =
                worker(runAttemptCount = 0) { _, _, _ ->
                    liveDuringRetry = ledger.liveCount()
                    true
                }.doWork()

            assertEquals(Result.success(), retry)
            assertEquals(1, liveDuringRetry)
            assertEquals(0, ledger.liveCount())
            assertEquals(listOf("stopped", "completed"), outcomes())
            assertEquals(listOf("40", "0"), lines.beginAttempts())
            assertEquals(listOf(1, 0, 1, 0), liveCounts)
        }

    /** Concurrent explicit transfers raise the count to two, and each releases only itself. */
    @Test
    fun concurrentTransfersRaiseAndLowerTheCount() =
        runTest {
            markInteractive()
            val other = request.copy(attachmentIndex = 1)
            attachmentIntentStore(appContext).setInteractive(other, interactive = true)
            val gate = CompletableDeferred<Unit>()
            val both = CompletableDeferred<Unit>()
            val first = async { worker(0, request) { _, _, _ -> awaitBoth(both, gate) }.doWork() }
            val second = async { worker(0, other) { _, _, _ -> awaitBoth(both, gate) }.doWork() }
            runCurrent()

            assertEquals(2, ledger.liveCount())
            gate.complete(Unit)
            runCurrent()

            assertEquals(Result.success(), first.await())
            assertEquals(Result.success(), second.await())
            assertEquals(0, ledger.liveCount())
            assertEquals(listOf(1, 2, 1, 0), liveCounts)
        }

    /** Waits until both transfers have started, then until the gate opens, and retains the body. */
    private suspend fun awaitBoth(
        both: CompletableDeferred<Unit>,
        gate: CompletableDeferred<Unit>,
    ): Boolean {
        if (ledger.liveCount() == 2) both.complete(Unit)
        gate.await()
        return true
    }

    /** Records the reader's explicit request so the worker runs at interactive priority. */
    private fun markInteractive() = attachmentIntentStore(appContext).also { it.setInteractive(request, true) }

    /** A worker for [target] that runs [download] in place of the native fetch. */
    private fun worker(
        runAttemptCount: Int,
        target: AttachmentTransferRequest = request,
        download: PerformDurableAttachmentDownload,
    ): AttachmentDownloadWorker =
        TestListenableWorkerBuilder
            .from<AttachmentDownloadWorker>(appContext, AttachmentDownloadWorker::class.java)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker? =
                        if (workerClassName == AttachmentDownloadWorker::class.java.name) {
                            AttachmentDownloadWorker(appContext, workerParameters, download)
                        } else {
                            null
                        }
                },
            ).setInputData(AttachmentDownloadWorkData.encode(target))
            .setRunAttemptCount(runAttemptCount)
            .build()

    private val request =
        AttachmentTransferRequest(
            accountRef = "account-a",
            groupIdHex = "ab".repeat(16),
            messageIdHex = "cd".repeat(32),
            attachmentIndex = 0,
        )
}
