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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Waiting automatic work queued on a narrowed network widens in place when a receipt or the policy allows more. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticAttachmentNetworkWaitsTest {
    private lateinit var context: Context
    private val waits = AutomaticAttachmentNetworkWaits(VolatileAttachmentInstallerHandoffRecordStore())
    private val settles = CountingExecutor()
    private var enqueued = 0

    /** Starts from a clean test WorkManager, whose constraints keep enqueued work waiting, and a volatile registry. */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AutomaticAttachmentNetworkWaits.installForTest(waits)
        AttachmentDownloadWorker.receiptSettleExecutor = settles
    }

    /** Restores the process registry so later tests never see this one. */
    @After
    fun tearDown() {
        AutomaticAttachmentNetworkWaits.installForTest(null)
        AutomaticAttachmentNetworkWaits.policyNetworkType = null
        AttachmentDownloadWorker.receiptSettleExecutor = Dispatchers.IO.asExecutor()
        settles.shutdown()
    }

    /**
     * The wait is registered before the enqueue lands, so a policy that widens before the receipt settles still
     * finds a record, and its sweep widens the spec rather than leaving it on Wi-Fi.
     */
    @Test
    fun aWaitRegisteredBeforeItsEnqueueLetsAnEarlySweepWidenIt() {
        val held = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        AttachmentDownloadWorker.receiptSettleExecutor =
            Executor { command ->
                held.execute {
                    gate.await()
                    command.run()
                }
            }
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)

        assertEquals(
            "registered before the settle",
            listOf(NetworkType.UNMETERED),
            waits.waiting(ACCOUNT).map { it.networkType },
        )
        assertEquals(1, waits.widen(manager(), ACCOUNT) { NetworkType.CONNECTED })
        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
        gate.countDown()
        held.shutdown()
        held.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
    }

    /** A receipt settles against the live policy, so one that widened while the enqueue landed is applied at once. */
    @Test
    fun aReceiptSettlesAgainstThePolicyAsItIsNow() {
        AutomaticAttachmentNetworkWaits.policyNetworkType =
            { account, _ -> NetworkType.CONNECTED.takeIf { account == ACCOUNT } }

        receipt(NetworkType.UNMETERED)

        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
        assertTrue("work on any network keeps no record", waits.waiting(ACCOUNT).isEmpty())
    }

    /**
     * A worker narrowing a deferred run applies the live policy, not the network it captured, so mobile downloads
     * enabled while it was deferring are not overwritten by its older Wi-Fi restriction.
     */
    @Test
    fun aDeferredNarrowingAppliesTheLivePolicyNotItsCapturedNetwork() {
        receipt(NetworkType.CONNECTED)
        val id = single().id
        AutomaticAttachmentNetworkWaits.policyNetworkType = { _, _ -> NetworkType.CONNECTED }

        waits.narrow(manager(), request, IMAGE, id, captured = NetworkType.UNMETERED)

        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
        assertTrue("work on any network keeps no record", waits.waiting(ACCOUNT).isEmpty())
    }

    /**
     * A sweep that read an older policy and finishes after a newer one never writes back the older network: every
     * update applies the live policy under one lock, so a stale "no roaming" cannot follow "any connection".
     */
    @Test
    fun anOlderPolicyUpdateNeverOverwritesANewerOne() {
        receipt(NetworkType.UNMETERED)
        AutomaticAttachmentNetworkWaits.policyNetworkType = { _, _ -> NetworkType.CONNECTED }

        waits.widen(manager(), ACCOUNT) { NetworkType.NOT_ROAMING }

        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
    }

    /**
     * A registration that cannot reach disk never leaves narrowed work without a record: the work is queued on any
     * network instead, so its own run re-checks the policy and narrows itself through the registry.
     */
    @Test
    fun anUnsavedRegistrationQueuesTheWorkOnAnyNetwork() {
        val rejecting =
            object : AttachmentInstallerHandoffRecordStore {
                override fun readAll(): Map<String, String> = emptyMap()

                override fun replaceAllDurably(values: Map<String, String>): Boolean = false
            }
        AutomaticAttachmentNetworkWaits.installForTest(AutomaticAttachmentNetworkWaits(rejecting))

        receipt(NetworkType.UNMETERED)

        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
    }

    /** An installed policy hook that cannot answer, for an unloaded account, never lets a receipt widen the spec. */
    @Test
    fun aReceiptNeverWidensForAnAccountThePolicyCannotAnswer() {
        receipt(NetworkType.UNMETERED)
        AutomaticAttachmentNetworkWaits.policyNetworkType = { _, _ -> null }

        receipt(NetworkType.CONNECTED)

        assertEquals(NetworkType.UNMETERED, single().constraints.requiredNetworkType)
        assertEquals(listOf(NetworkType.UNMETERED), waits.waiting(ACCOUNT).map { it.networkType })
    }

    /** A narrowing to any network that fails to update the spec keeps its record, so a later sweep can widen it. */
    @Test
    fun aFailedNarrowingToAnyNetworkKeepsItsRecord() {
        receipt(NetworkType.UNMETERED)

        runCatching {
            waits.narrow(manager(), request, IMAGE, java.util.UUID.randomUUID(), captured = NetworkType.CONNECTED)
        }

        assertEquals(listOf(NetworkType.UNMETERED), waits.waiting(ACCOUNT).map { it.networkType })
    }

    /** A registration whose work never landed survives a sweep within its grace period and is pruned after it. */
    @Test
    fun aRegistrationWithoutWorkIsKeptForItsGraceAndThenPruned() {
        waits.register(request, IMAGE, NetworkType.UNMETERED)
        waits.widen(manager(), ACCOUNT) { NetworkType.CONNECTED }
        assertEquals("kept within its grace", 1, waits.waiting(ACCOUNT).size)

        val other = request.copy(attachmentIndex = 1)
        waits.register(other, IMAGE, NetworkType.UNMETERED, nowMs = 0L)
        waits.widen(manager(), ACCOUNT) { NetworkType.CONNECTED }
        assertEquals("the stale one is pruned", listOf(request), waits.waiting(ACCOUNT).map { it.request })
    }

    /**
     * A duplicate receipt for a download already running on Wi-Fi widens that running spec for its next run, so
     * when Android interrupts it the retry waits on any network, and the receipt never drops the record before
     * the spec itself was widened.
     */
    @Test
    fun duplicateReceiptWidensRunningWorkSoAnInterruptionKeepsTheWiderNetwork() {
        val running = holdNextRun()
        receipt(NetworkType.UNMETERED)
        val id = single().id
        assertEquals(listOf(NetworkType.UNMETERED), waits.waiting(ACCOUNT).map { it.networkType })
        checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(id)
        assertTrue("the download started", running.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        awaitState(WorkInfo.State.RUNNING)

        receipt(NetworkType.CONNECTED)
        running.release.countDown()
        awaitState(WorkInfo.State.ENQUEUED)

        assertEquals(id, single().id)
        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
        assertTrue("the widened spec needs no record", waits.waiting(ACCOUNT).isEmpty())
    }

    /**
     * Enqueues one automatic image receipt on [networkType] and waits for its settle to finish, so the spec and
     * the record can be read without racing the background widening.
     */
    private fun receipt(networkType: NetworkType) {
        AttachmentDownloadWorker.enqueue(context, request, networkType = networkType, type = IMAGE)
        enqueued += 1
        settles.awaitCompleted(enqueued)
    }

    /** Runs each settle on a background thread and counts how many have finished. */
    private class CountingExecutor : Executor {
        private val pool = Executors.newSingleThreadExecutor()
        private val completed = AtomicInteger()

        /** Runs [command] on the pool and counts it once it has finished, failed or not. */
        override fun execute(command: Runnable) {
            pool.execute {
                try {
                    command.run()
                } finally {
                    completed.incrementAndGet()
                }
            }
        }

        /** Waits until at least [count] settles have finished, failing after the shared timeout. */
        fun awaitCompleted(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (completed.get() < count) {
                check(System.nanoTime() < deadline) { "only ${completed.get()} of $count receipts settled" }
                Thread.sleep(POLL_MILLIS)
            }
        }

        /** Stops the pool. */
        fun shutdown() = pool.shutdownNow()
    }

    /** A duplicate receipt asking for a wider network widens the waiting spec in place, keeping its id. */
    @Test
    fun duplicateReceiptWidensTheWaitingSpecInPlace() {
        receipt(NetworkType.UNMETERED)
        val queued = single()
        assertEquals(NetworkType.UNMETERED, queued.constraints.requiredNetworkType)

        receipt(NetworkType.NOT_ROAMING)

        val widened = single()
        assertEquals(queued.id, widened.id)
        assertEquals(WorkInfo.State.ENQUEUED, widened.state)
        assertEquals(NetworkType.NOT_ROAMING, widened.constraints.requiredNetworkType)
    }

    /** A duplicate receipt never narrows a spec: a narrower request leaves the wider one waiting. */
    @Test
    fun duplicateReceiptNeverNarrowsTheWaitingSpec() {
        receipt(NetworkType.CONNECTED)

        receipt(NetworkType.UNMETERED)

        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
    }

    /** A policy that now allows a wider network widens the recorded spec without any new receipt. */
    @Test
    fun widerPolicyWidensTheRecordedSpecWithoutANewReceipt() {
        receipt(NetworkType.UNMETERED)
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
        receipt(NetworkType.NOT_ROAMING)
        val queued = single()

        assertEquals(1, waits.widen(manager(), ACCOUNT) { NetworkType.UNMETERED })
        assertEquals(queued.id, single().id)
        assertEquals(NetworkType.UNMETERED, single().constraints.requiredNetworkType)

        waits.widen(manager(), ACCOUNT) { NetworkType.NOT_ROAMING }
        assertEquals(NetworkType.NOT_ROAMING, single().constraints.requiredNetworkType)
        receipt(NetworkType.UNMETERED)

        assertEquals(queued.id, single().id)
        assertEquals(NetworkType.UNMETERED, single().constraints.requiredNetworkType)
    }

    /** Another account's policy, and a policy that is no wider, leave the spec as it is. */
    @Test
    fun onlyAWiderPolicyForTheSameAccountWidens() {
        receipt(NetworkType.UNMETERED)

        assertEquals(0, waits.widen(manager(), "account-b") { NetworkType.CONNECTED })
        assertEquals(0, waits.widen(manager(), ACCOUNT) { NetworkType.UNMETERED })

        assertEquals(NetworkType.UNMETERED, single().constraints.requiredNetworkType)
    }

    /** Retired work is never widened or resurrected, and its record is pruned once nothing waits. */
    @Test
    fun retiredWorkIsNeverWidenedAndItsRecordIsPruned() {
        receipt(NetworkType.UNMETERED)
        AttachmentDownloadWorker.retireWork(context, request)
        manager().cancelUniqueWork(attachmentDownloadWorkName(request)).result.get()

        assertEquals(0, waits.widen(manager(), ACCOUNT) { NetworkType.CONNECTED })

        assertTrue(infos().all { it.state.isFinished })
        assertTrue(waits.waiting(ACCOUNT).isEmpty())
    }

    /** The registry keeps narrowed waits only, so work queued on any network leaves nothing behind. */
    @Test
    fun workQueuedOnAnyNetworkIsNotRecorded() {
        receipt(NetworkType.CONNECTED)

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
        receipt(NetworkType.UNMETERED)
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
        receipt(NetworkType.UNMETERED)
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
