package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import java.util.UUID

/** The WorkManager fallback counts its transfer card while it runs and reports how each run ended. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTestApplication::class)
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
        // The Keystore-sealed registry cannot save under Robolectric, and a narrowing needs its wait recorded.
        AutomaticAttachmentNetworkWaits.installForTest(
            AutomaticAttachmentNetworkWaits(VolatileAttachmentInstallerHandoffRecordStore()),
        )
    }

    /** Restores the inert ledger so later tests never see this one. */
    @After
    fun tearDown() {
        AttachmentTransfers.installForTest(null)
        AutomaticAttachmentNetworkWaits.installForTest(null)
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

    /** Automatic work the live policy now holds back finishes as success, so nothing retries it (#3308). */
    @Test
    fun automaticWorkDeferredByLivePolicyFinishesWithoutRetry() =
        runTest {
            AutomaticAttachmentHold.entries.filterNot { it.waits }.forEach { hold ->
                val download: PerformDurableAttachmentDownload = { _, _, _ ->
                    throw AttachmentAutomaticPolicyDeferredException(MediaAutoDownloadType.Video, hold)
                }

                assertEquals(hold.reason, Result.success(), worker(0, request, download).doWork())
            }
            assertFalse(attachmentIntentStore(appContext).hasSpentTransientRetry(request))
            assertTrue(lines.isEmpty())
        }

    /** Work held because no account list has loaded yet retries rather than finishing without a download. */
    @Test
    fun workHeldByAnUnloadedAccountListRetries() =
        runTest {
            val download: PerformDurableAttachmentDownload = { _, _, _ ->
                throw AttachmentAutomaticPolicyDeferredException(null, AutomaticAttachmentHold.AccountsUnloaded)
            }

            assertEquals(Result.retry(), worker(0, request, download).doWork())
        }

    /**
     * Work held only by the network keeps waiting for an allowed one however many runs that takes, without
     * spending its failure retry, so a long stretch on a refused network never ends it without a download.
     */
    @Test
    fun networkHeldAutomaticWorkNeverExpiresWithoutDownloading() =
        runTest {
            val download: PerformDurableAttachmentDownload = { _, _, _ ->
                throw AttachmentAutomaticPolicyDeferredException(
                    MediaAutoDownloadType.Video,
                    AutomaticAttachmentHold.Network,
                    NetworkType.UNMETERED,
                )
            }

            listOf(0, 1, 7, 8, 9, 40, 500).forEach { run ->
                assertEquals("run $run", Result.retry(), worker(run, request, download).doWork())
            }
            assertFalse(attachmentIntentStore(appContext).hasSpentTransientRetry(request))
        }

    /**
     * A network hold narrows the queued spec to the network its live policy could allow, so work queued wider,
     * as on a cold start before the account loaded, waits in WorkManager instead of waking on a refused network.
     */
    @Test
    fun networkHeldAutomaticWorkNarrowsItsQueuedNetworkConstraint() =
        runTest {
            AttachmentDownloadWorker.enqueue(appContext, request, networkType = NetworkType.CONNECTED)
            val queued = queuedWork()
            assertEquals(NetworkType.CONNECTED, queued.constraints.requiredNetworkType)
            val download: PerformDurableAttachmentDownload = { _, _, _ ->
                throw AttachmentAutomaticPolicyDeferredException(
                    MediaAutoDownloadType.Video,
                    AutomaticAttachmentHold.Network,
                    NetworkType.UNMETERED,
                )
            }

            assertEquals(Result.retry(), worker(3, request, id = queued.id, download = download).doWork())

            assertEquals(NetworkType.UNMETERED, queuedWork().constraints.requiredNetworkType)
        }

    /**
     * An approval the runtime had not applied yet is a network change still settling: the work waits for it on
     * every run without spending the failure retry, rather than failing after one follow-up (#3308).
     */
    @Test
    fun pendingPermissionWaitsWithoutSpendingTheFailureRetry() =
        runTest {
            val download: PerformDurableAttachmentDownload = { _, _, _ -> throw AttachmentPermissionPendingException() }

            listOf(0, 1, 2, 30).forEach { run ->
                assertEquals("run $run", Result.retry(), worker(run, request, download).doWork())
            }
            assertFalse(attachmentIntentStore(appContext).hasSpentTransientRetry(request))
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

    /** The single WorkManager spec queued for [request]. */
    private fun queuedWork(): WorkInfo =
        WorkManager
            .getInstance(appContext)
            .getWorkInfosByTag(attachmentIdentityTag(request))
            .get()
            .single()

    /** A worker for [target] that runs [download] in place of the native fetch. */
    private fun worker(
        runAttemptCount: Int,
        target: AttachmentTransferRequest = request,
        download: PerformDurableAttachmentDownload,
    ): AttachmentDownloadWorker = worker(runAttemptCount, target, id = null, download = download)

    /** A worker for [target] bound to the WorkManager spec [id], so its updates reach that queued spec. */
    private fun worker(
        runAttemptCount: Int,
        target: AttachmentTransferRequest,
        id: UUID?,
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
            .apply { if (id != null) setId(id) }
            .build()

    private val request =
        AttachmentTransferRequest(
            accountRef = "account-a",
            groupIdHex = "ab".repeat(16),
            messageIdHex = "cd".repeat(32),
            attachmentIndex = 0,
        )
}
