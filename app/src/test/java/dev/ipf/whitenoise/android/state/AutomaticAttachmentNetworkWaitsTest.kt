package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Waiting automatic work queued on a narrowed network widens in place when a receipt or the policy allows more. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticAttachmentNetworkWaitsTest {
    private lateinit var context: Context
    private val waits = AutomaticAttachmentNetworkWaits(VolatileAttachmentInstallerHandoffRecordStore())

    /** Starts from a clean test WorkManager, whose constraints keep enqueued work waiting, and a volatile registry. */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AutomaticAttachmentNetworkWaits.installForTest(waits)
    }

    /** Restores the process registry so later tests never see this one. */
    @After
    fun tearDown() {
        AutomaticAttachmentNetworkWaits.installForTest(null)
    }

    /** A duplicate receipt asking for a wider network widens the waiting spec in place, keeping its id. */
    @Test
    fun duplicateReceiptWidensTheWaitingSpecInPlace() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)
        val queued = single()
        assertEquals(NetworkType.UNMETERED, queued.constraints.requiredNetworkType)

        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.NOT_ROAMING, type = IMAGE)

        val widened = single()
        assertEquals(queued.id, widened.id)
        assertEquals(WorkInfo.State.ENQUEUED, widened.state)
        assertEquals(NetworkType.NOT_ROAMING, widened.constraints.requiredNetworkType)
    }

    /** A duplicate receipt never narrows a spec: a narrower request leaves the wider one waiting. */
    @Test
    fun duplicateReceiptNeverNarrowsTheWaitingSpec() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.CONNECTED, type = IMAGE)

        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)

        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
    }

    /** A policy that now allows a wider network widens the recorded spec without any new receipt. */
    @Test
    fun widerPolicyWidensTheRecordedSpecWithoutANewReceipt() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)
        val queued = single()
        assertEquals(listOf(NetworkType.UNMETERED), waits.waiting(ACCOUNT).map { it.networkType })

        val widened = waits.widen(manager(), ACCOUNT) { NetworkType.NOT_ROAMING }

        assertEquals(1, widened)
        assertEquals(queued.id, single().id)
        assertEquals(NetworkType.NOT_ROAMING, single().constraints.requiredNetworkType)
        assertEquals(listOf(NetworkType.NOT_ROAMING), waits.waiting(ACCOUNT).map { it.networkType })

        waits.widen(manager(), ACCOUNT) { NetworkType.CONNECTED }
        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
        assertTrue("work on any network is no longer recorded", waits.waiting(ACCOUNT).isEmpty())
    }

    /** A not-roaming wait takes an unmetered policy or receipt, which admits the unmetered roaming it refused. */
    @Test
    fun notRoamingWaitTakesAnUnmeteredPolicyOrReceipt() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.NOT_ROAMING, type = IMAGE)
        val queued = single()

        assertEquals(1, waits.widen(manager(), ACCOUNT) { NetworkType.UNMETERED })
        assertEquals(queued.id, single().id)
        assertEquals(NetworkType.UNMETERED, single().constraints.requiredNetworkType)

        waits.widen(manager(), ACCOUNT) { NetworkType.NOT_ROAMING }
        assertEquals(NetworkType.NOT_ROAMING, single().constraints.requiredNetworkType)
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)

        assertEquals(queued.id, single().id)
        assertEquals(NetworkType.UNMETERED, single().constraints.requiredNetworkType)
    }

    /** Another account's policy, and a policy that is no wider, leave the spec as it is. */
    @Test
    fun onlyAWiderPolicyForTheSameAccountWidens() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)

        assertEquals(0, waits.widen(manager(), "account-b") { NetworkType.CONNECTED })
        assertEquals(0, waits.widen(manager(), ACCOUNT) { NetworkType.UNMETERED })

        assertEquals(NetworkType.UNMETERED, single().constraints.requiredNetworkType)
    }

    /** Retired work is never widened or resurrected, and its record is pruned once nothing waits. */
    @Test
    fun retiredWorkIsNeverWidenedAndItsRecordIsPruned() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)
        AttachmentDownloadWorker.retireWork(context, request)
        manager().cancelUniqueWork(attachmentDownloadWorkName(request)).result.get()

        assertEquals(0, waits.widen(manager(), ACCOUNT) { NetworkType.CONNECTED })

        assertTrue(infos().all { it.state.isFinished })
        assertTrue(waits.waiting(ACCOUNT).isEmpty())
    }

    /** The registry keeps narrowed waits only, so work queued on any network leaves nothing behind. */
    @Test
    fun workQueuedOnAnyNetworkIsNotRecorded() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.CONNECTED, type = IMAGE)

        assertTrue(waits.waiting(ACCOUNT).isEmpty())
    }

    /**
     * A download already running on Wi-Fi when the policy widens to any network keeps that wider network after
     * Android interrupts it: the running spec takes the update for its next run, so the retry no longer waits for
     * Wi-Fi, and the record is dropped only because the spec itself was widened.
     */
    @Test
    fun runningWorkWidenedByThePolicyKeepsTheWiderNetworkAfterAnInterruption() {
        val running = holdNextRun()
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)
        val id = single().id
        checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(id)
        assertTrue("the download started", running.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        awaitState(WorkInfo.State.RUNNING)

        assertEquals(1, waits.widen(manager(), ACCOUNT) { NetworkType.CONNECTED })
        running.release.countDown()
        awaitState(WorkInfo.State.ENQUEUED)

        assertEquals(id, single().id)
        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
        assertTrue("the widened spec needs no record", waits.waiting(ACCOUNT).isEmpty())
    }

    /** A running download widened only off roaming keeps its record at the new network for a later widening. */
    @Test
    fun runningWorkKeepsItsRecordAtTheNetworkItWasWidenedTo() {
        val running = holdNextRun()
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)
        checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(single().id)
        assertTrue("the download started", running.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        awaitState(WorkInfo.State.RUNNING)

        waits.widen(manager(), ACCOUNT) { NetworkType.NOT_ROAMING }
        running.release.countDown()
        awaitState(WorkInfo.State.ENQUEUED)

        assertEquals(NetworkType.NOT_ROAMING, single().constraints.requiredNetworkType)
        assertEquals(listOf(NetworkType.NOT_ROAMING), waits.waiting(ACCOUNT).map { it.networkType })
    }

    /**
     * Reinitialises the test WorkManager so the next download run blocks on a latch on its own thread, keeping
     * the work RUNNING until released, and then returns a retry as an interrupted transfer would.
     */
    private fun holdNextRun(): HeldRun {
        val held = HeldRun()
        val configuration =
            Configuration
                .Builder()
                .setExecutor(Executors.newSingleThreadExecutor())
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker = HeldWorker(appContext, workerParameters, held)
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        return held
    }

    /** Polls until the request's spec reaches [state], failing after the shared timeout. */
    private fun awaitState(state: WorkInfo.State) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (single().state != state) {
            check(System.nanoTime() < deadline) { "work never reached $state, it is ${single().state}" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** The latches one held download run signals and waits on. */
    private class HeldRun {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    /** Stands in for the download: reports it started, waits to be released, then asks to be retried. */
    private class HeldWorker(
        context: Context,
        parameters: WorkerParameters,
        private val held: HeldRun,
    ) : Worker(context, parameters) {
        /** Holds the run open until the test releases it, then retries as an interrupted transfer would. */
        override fun doWork(): Result {
            held.started.countDown()
            held.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return Result.retry()
        }
    }

    /** The test WorkManager. */
    private fun manager(): WorkManager = WorkManager.getInstance(context)

    /** Every spec under the request's unique name. */
    private fun infos(): List<WorkInfo> = manager().getWorkInfosForUniqueWork(attachmentDownloadWorkName(request)).get()

    /** The one spec under the request's unique name. */
    private fun single(): WorkInfo = infos().single()

    private companion object {
        const val ACCOUNT = "account-a"
        const val TIMEOUT_SECONDS = 10L
        const val POLL_MILLIS = 20L
        val IMAGE = MediaAutoDownloadType.Image
        val request =
            AttachmentTransferRequest(
                accountRef = ACCOUNT,
                groupIdHex = "ab".repeat(16),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 0,
            )
    }
}
