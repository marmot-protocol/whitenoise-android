package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Clock
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the real WorkManager scheduler with a controllable clock to show how Android stops of
 * attachment downloads are rescheduled, with and without the interruption-backoff opt-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = WhiteNoiseApplication::class)
class AttachmentDownloadInterruptionBackoffTest {
    private lateinit var application: WhiteNoiseApplication
    private val clock = VirtualClock()

    /** Run-attempt counts observed at each worker start, in order. */
    private val starts = mutableListOf<Int>()
    private val completions = AtomicInteger(0)
    private val entered = AtomicInteger(0)

    @Volatile private var finishDownloads = false

    /** Starts the test WorkManager with a download that blocks until the platform stops it. */
    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker {
                    synchronized(starts) { starts += workerParameters.runAttemptCount }
                    if (workerClassName == BlockingWorker::class.java.name) {
                        return BlockingWorker(appContext, workerParameters, entered)
                    }
                    return AttachmentDownloadWorker(appContext, workerParameters) { _, _, _ ->
                        entered.incrementAndGet()
                        if (finishDownloads) {
                            completions.incrementAndGet()
                            true
                        } else {
                            awaitCancellation()
                        }
                    }
                }
            }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            application,
            Configuration
                .Builder()
                .setWorkerFactory(factory)
                .setClock(clock)
                .build(),
        )
    }

    /** Ordinary work opts into interruption backoff and says so in its input; explicit work does not. */
    @Test
    fun ordinaryRequestOptsIntoInterruptionBackoffAndExplicitRequestDoesNot() {
        val ordinary = AttachmentDownloadWorker.buildRequest(testRequest(), AttachmentDownloadPriority.Automatic)
        val explicit = AttachmentDownloadWorker.buildRequest(testRequest(), AttachmentDownloadPriority.Interactive)

        assertEquals(true, ordinary.workSpec.backOffOnSystemInterruptions)
        assertTrue(AttachmentDownloadWorkData.hasInterruptionBackoff(ordinary.workSpec.input))
        assertEquals(BackoffPolicy.EXPONENTIAL, ordinary.workSpec.backoffPolicy)
        assertEquals(
            TimeUnit.SECONDS.toMillis(AttachmentDownloadWorker.BACKOFF_SECONDS),
            ordinary.workSpec.backoffDelayDuration,
        )
        assertTrue(attachmentAutomaticAccountTag(testRequest().accountRef) in ordinary.tags)

        assertNotEquals(true, explicit.workSpec.backOffOnSystemInterruptions)
        assertFalse(AttachmentDownloadWorkData.hasInterruptionBackoff(explicit.workSpec.input))
        assertFalse(attachmentAutomaticAccountTag(testRequest().accountRef) in explicit.tags)
        assertEquals(testRequest(), AttachmentDownloadWorkData.decode(ordinary.workSpec.input))
    }

    /** Repeated platform stops of ordinary work resume after a doubling delay that stops at WorkManager's ceiling. */
    @Test
    fun repeatedQuotaStopsOfOrdinaryWorkBackOffUpToTheCeiling() {
        AttachmentDownloadWorker.enqueue(application, testRequest())
        val id = uniqueWorkId()
        val delays = mutableListOf<Long>()

        repeat(INTERRUPTION_ROUNDS) { round ->
            startAndStop(id, STOP_REASON_QUOTA)
            val stopped = snapshot(id)
            assertEquals(WorkInfo.State.ENQUEUED, stopped.state)
            assertEquals(round + 1, stopped.runAttemptCount)
            val delay = stopped.nextRunMs - clock.now
            delays += delay
            clock.now += delay
        }

        val expected =
            List(INTERRUPTION_ROUNDS) { round ->
                minOf(TimeUnit.SECONDS.toMillis(30) shl round, TimeUnit.HOURS.toMillis(5))
            }
        assertEquals(expected, delays)
        assertEquals(TimeUnit.HOURS.toMillis(5), delays.last())
    }

    /**
     * Without the opt-in WorkManager measures the retry delay from the spec's original enqueue time,
     * so once a spec is older than the five-hour ceiling every stop leaves it due again at once.
     * That is how a single transfer reached more than a thousand runs.
     */
    @Test
    fun agedWorkWithoutTheOptInRestartsImmediatelyAfterEveryStop() {
        val id = enqueueBlockingWork(optIn = false)
        clock.now += AGED_MS

        repeat(INTERRUPTION_ROUNDS) {
            startAndStop(id, STOP_REASON_QUOTA)
            assertTrue(snapshot(id).nextRunMs <= clock.now)
        }
        assertEquals(INTERRUPTION_ROUNDS, entered.get())
    }

    /** A pre-upgrade spec is updated in place the first time it runs, so even its first stop is delayed. */
    @Test
    fun preUpgradeSpecAdoptsTheBackoffWhenItNextRuns() {
        enqueuePreUpgradeSpec()
        val id = uniqueWorkId()

        startAndStop(id, STOP_REASON_QUOTA)
        assertEquals(id, uniqueWorkId())
        assertEquals(TimeUnit.SECONDS.toMillis(30), snapshot(id).nextRunMs - clock.now)

        clock.now = snapshot(id).nextRunMs
        startAndStop(id, STOP_REASON_QUOTA)
        assertEquals(id, uniqueWorkId())
        assertEquals(TimeUnit.SECONDS.toMillis(60), snapshot(id).nextRunMs - clock.now)
    }

    /** A spec that was cancelled while it ran is not recreated by the upgrade. */
    @Test
    fun adoptionNeverRecreatesCancelledWork() {
        enqueuePreUpgradeSpec()
        val id = uniqueWorkId()
        startAndStop(id, STOP_REASON_QUOTA)
        AttachmentDownloadWorker.cancelForRequest(application, testRequest())

        val infos = WorkManager.getInstance(application).getWorkInfosForUniqueWork(workName()).get()
        assertTrue(infos.all { it.state.isFinished })
    }

    /** Backed-off work completes once the delay has passed and the platform lets it run. */
    @Test
    fun stoppedWorkCompletesAfterItsDelayAndClearsItsRetryBudget() {
        AttachmentDownloadWorker.enqueue(application, testRequest())
        val id = uniqueWorkId()
        startAndStop(id, STOP_REASON_CONSTRAINT)
        val intents = intentStore()
        intents.spendTransientRetry(testRequest())
        clock.now = snapshot(id).nextRunMs
        finishDownloads = true

        WorkManagerTestInitHelper.getTestDriver(application)!!.setAllConstraintsMet(id)
        eventually { workInfo(id).state == WorkInfo.State.SUCCEEDED }

        assertEquals(1, completions.get())
        assertEquals(listOf(0, 1), synchronized(starts) { starts.toList() })
        assertFalse(intents.hasSpentTransientRetry(testRequest()))
    }

    /** A fresh explicit request replaces backed-off automatic work instead of waiting behind it. */
    @Test
    fun explicitRequestReplacesBackedOffAutomaticWork() {
        AttachmentDownloadWorker.enqueue(application, testRequest())
        val automaticId = uniqueWorkId()
        repeat(4) {
            startAndStop(automaticId, STOP_REASON_QUOTA)
            clock.now = snapshot(automaticId).nextRunMs
        }
        assertTrue(snapshot(automaticId).runAttemptCount > 0)

        AttachmentDownloadWorker.enqueue(application, testRequest(), AttachmentDownloadPriority.Interactive)

        val explicitId = uniqueWorkId()
        assertNotEquals(automaticId, explicitId)
        val replaced = WorkManager.getInstance(application).getWorkInfoById(automaticId).get()
        assertTrue(replaced == null || replaced.state == WorkInfo.State.CANCELLED)
        val explicit = snapshot(explicitId)
        assertEquals(WorkInfo.State.ENQUEUED, explicit.state)
        assertEquals(0, explicit.runAttemptCount)
        assertFalse(attachmentAutomaticAccountTag(testRequest().accountRef) in workInfo(explicitId).tags)
    }

    /** A failed lookup is retried once, and the retry still replaces the waiting automatic work. */
    @Test
    fun failedLookupIsRetriedOnceBeforeChoosingAPolicy() {
        AttachmentDownloadWorker.enqueue(application, testRequest())
        val automaticId = uniqueWorkId()
        intentStore().setInteractive(testRequest(), interactive = true)
        val lookups = AtomicInteger(0)

        AttachmentDownloadWorker.enqueueExplicit(
            application,
            testRequest(),
            AttachmentDownloadWorker.buildRequest(testRequest(), AttachmentDownloadPriority.Interactive),
            intentStore(),
            lookup = { manager, name ->
                if (lookups.incrementAndGet() == 1) failedLookup() else manager.getWorkInfosForUniqueWork(name)
            },
        )

        assertEquals(2, lookups.get())
        assertNotEquals(automaticId, uniqueWorkId())
    }

    /** Two failed lookups enqueue nothing, leave the waiting work alone and keep the explicit intent. */
    @Test
    fun repeatedLookupFailureEnqueuesNothingAndKeepsTheIntent() {
        AttachmentDownloadWorker.enqueue(application, testRequest())
        val automaticId = uniqueWorkId()
        intentStore().setInteractive(testRequest(), interactive = true)
        val lookups = AtomicInteger(0)

        AttachmentDownloadWorker.enqueueExplicit(
            application,
            testRequest(),
            AttachmentDownloadWorker.buildRequest(testRequest(), AttachmentDownloadPriority.Interactive),
            intentStore(),
            lookup = { _, _ ->
                lookups.incrementAndGet()
                failedLookup()
            },
        )

        assertEquals(2, lookups.get())
        assertEquals(automaticId, uniqueWorkId())
        assertEquals(WorkInfo.State.ENQUEUED, workInfo(automaticId).state)
        assertTrue(intentStore().isInteractive(testRequest()))
    }

    /** Repeated explicit requests and later automatic enqueues coalesce onto the one unique work. */
    @Test
    fun explicitAndAutomaticEnqueuesCoalesceOnTheUniqueWork() {
        AttachmentDownloadWorker.enqueue(application, testRequest(), AttachmentDownloadPriority.Interactive)
        val explicitId = uniqueWorkId()

        AttachmentDownloadWorker.enqueue(application, testRequest(), AttachmentDownloadPriority.Interactive)
        AttachmentDownloadWorker.enqueue(application, testRequest())

        assertEquals(explicitId, uniqueWorkId())
        assertEquals(
            1,
            WorkManager
                .getInstance(application)
                .getWorkInfosForUniqueWork(workName())
                .get()
                .size,
        )
    }

    /** Cancelling a request removes its work and restores the retry follow-up. */
    @Test
    fun cancelRemovesTheWorkAndRestoresTheRetryBudget() {
        AttachmentDownloadWorker.enqueue(application, testRequest())
        intentStore().spendTransientRetry(testRequest())

        AttachmentDownloadWorker.cancelForRequest(application, testRequest())

        val infos = WorkManager.getInstance(application).getWorkInfosForUniqueWork(workName()).get()
        assertTrue(infos.all { it.state.isFinished })
        assertFalse(intentStore().hasSpentTransientRetry(testRequest()))
    }

    /**
     * Counts restarts over one virtual hour when the platform stops every ten-second run, with and
     * without the opt-in, and extends both to a day. This measures scheduler behavior only, not
     * bandwidth or battery.
     */
    @Test
    fun virtualHourRestartCountsWithAndWithoutTheOptIn() {
        AttachmentDownloadWorker.enqueue(application, testRequest())
        val optedIn = simulateStopEveryRun(uniqueWorkId())
        clock.now = START_MS
        val agedId = enqueueBlockingWork(optIn = false)
        clock.now += AGED_MS
        val without = simulateStopEveryRun(agedId)

        val offsets = optedIn.map { it / 1000 }
        println("virtual_hour starts_with_opt_in=${optedIn.size} starts_without=${without.size} offsets_s=$offsets")
        assertEquals(listOf(0L, 40L, 110L, 240L, 490L, 980L, 1950L), optedIn.map { it / 1000 })
        assertEquals(TimeUnit.HOURS.toSeconds(1) / RUN_SECONDS, without.size.toLong())
    }

    /** Runs the work, stopping it each time it starts, for one virtual hour. Returns start offsets in ms. */
    private fun simulateStopEveryRun(id: UUID): List<Long> {
        val origin = clock.now
        val windowEnd = origin + TimeUnit.HOURS.toMillis(1)
        val offsets = mutableListOf<Long>()
        var due = snapshot(id).nextRunMs
        while (clock.now < windowEnd && due < windowEnd) {
            clock.now = maxOf(clock.now, due)
            offsets += clock.now - origin
            startAndStop(id, STOP_REASON_QUOTA, TimeUnit.SECONDS.toMillis(RUN_SECONDS))
            due = snapshot(id).nextRunMs
        }
        return offsets
    }

    /** Starts the work, waits until its download is running, advances the clock, and stops it with [stopReason]. */
    private fun startAndStop(
        id: UUID,
        stopReason: Int,
        runMillis: Long = 0L,
    ) {
        val before = entered.get()
        val driver = WorkManagerTestInitHelper.getTestDriver(application)!!
        driver.setAllConstraintsMet(id)
        // Entering the download means doWork already ran its start-up steps, including the upgrade.
        eventually("download entered") { entered.get() > before }
        clock.now += runMillis
        driver.stopRunningWorkWithReason(id, stopReason)
        eventually("requeue after stop") {
            snapshot(id).let { it.state == WorkInfo.State.ENQUEUED && it.stopReason == stopReason }
        }
    }

    /** Polls [condition] until it holds, failing with the scheduler state if it never does. */
    private fun eventually(
        description: String = "condition",
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + EVENTUAL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(POLL_MS)
        }
        val infos =
            WorkManager.getInstance(application).getWorkInfosForUniqueWork(workName()).get().map { info ->
                val next = info.nextScheduleTimeMillis - clock.now
                "${info.id.toString().take(8)}:${info.state}/attempt=${info.runAttemptCount}/next=$next"
            }
        error("$description not met in time; starts=${synchronized(starts) { starts.toList() }} infos=$infos")
    }

    /** Enqueues a blocking job with the same spec shape as a download, with or without the opt-in. */
    private fun enqueueBlockingWork(optIn: Boolean): UUID {
        val work =
            OneTimeWorkRequestBuilder<BlockingWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    AttachmentDownloadWorker.BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                ).apply { if (optIn) setBackoffForSystemInterruptions() }
                .build()
        WorkManager.getInstance(application).enqueueUniqueWork(BLOCKING_NAME, ExistingWorkPolicy.KEEP, work)
        return work.id
    }

    /** Enqueues a download spec as the previous release built it, without the opt-in or input marker. */
    private fun enqueuePreUpgradeSpec() {
        val work =
            OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
                .setInputData(AttachmentDownloadWorkData.encode(testRequest()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    AttachmentDownloadWorker.BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                ).addTag(attachmentIdentityTag(testRequest()))
                .addTag(attachmentAutomaticAccountTag(testRequest().accountRef))
                .build()
        WorkManager.getInstance(application).enqueueUniqueWork(workName(), ExistingWorkPolicy.KEEP, work)
    }

    /** A lookup future that has already failed, as a closed or busy work database would produce. */
    private fun failedLookup(): ListenableFuture<List<WorkInfo>> {
        val future = SettableFuture.create<List<WorkInfo>>()
        future.setException(IllegalStateException("work database unavailable"))
        return future
    }

    /** Reads the current public WorkManager info for one work id. */
    private fun workInfo(id: UUID): WorkInfo = WorkManager.getInstance(application).getWorkInfoById(id).get()!!

    /** Captures the scheduler-visible fields that the assertions compare. */
    private fun snapshot(id: UUID): Snapshot {
        val info = workInfo(id)
        return Snapshot(info.state, info.runAttemptCount, info.stopReason, info.nextScheduleTimeMillis)
    }

    /** Returns the id of the live work under the download's unique name. */
    private fun uniqueWorkId(): UUID =
        WorkManager
            .getInstance(application)
            .getWorkInfosForUniqueWork(workName())
            .get()
            .first { !it.state.isFinished }
            .id

    /** Returns the unique WorkManager name of the test transfer. */
    private fun workName(): String = attachmentDownloadWorkName(testRequest())

    /** Opens the intent store the worker reads, on the same preferences file. */
    private fun intentStore(): AttachmentDownloadIntentStore {
        val preferences = application.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
        return AttachmentDownloadIntentStore(preferences)
    }

    /** Builds the fixed transfer identity used by every case. */
    private fun testRequest(): AttachmentTransferRequest =
        AttachmentTransferRequest(
            accountRef = "account-a",
            groupIdHex = "ab".repeat(16),
            messageIdHex = "cd".repeat(32),
            attachmentIndex = 0,
        )

    /** A worker that blocks until stopped, standing in for any job with the same platform spec shape. */
    class BlockingWorker(
        appContext: Context,
        params: WorkerParameters,
        private val entered: AtomicInteger,
    ) : CoroutineWorker(appContext, params) {
        /** Signals that the job started, then blocks until the platform stops it. */
        override suspend fun doWork(): Result {
            entered.incrementAndGet()
            awaitCancellation()
        }
    }

    /** The scheduler-visible state of one work item at a point in time. */
    private data class Snapshot(
        val state: WorkInfo.State,
        val runAttemptCount: Int,
        val stopReason: Int,
        val nextRunMs: Long,
    )

    /** A settable wall clock so backoff delays can be read from persisted specs without waiting. */
    private class VirtualClock : Clock {
        @Volatile var now: Long = START_MS

        /** Returns the settable virtual time. */
        override fun currentTimeMillis(): Long = now
    }

    private companion object {
        const val START_MS = 1_800_000_000_000L
        const val STOP_REASON_QUOTA = 10
        const val STOP_REASON_CONSTRAINT = 4
        const val INTERRUPTION_ROUNDS = 13
        const val RUN_SECONDS = 10L
        const val AGED_MS = 6L * 60L * 60L * 1000L
        const val BLOCKING_NAME = "blocking"
        const val EVENTUAL_TIMEOUT_MS = 15_000L
        const val POLL_MS = 10L
    }
}
