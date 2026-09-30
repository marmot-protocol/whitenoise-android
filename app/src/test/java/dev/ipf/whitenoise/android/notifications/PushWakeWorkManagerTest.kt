package dev.ipf.whitenoise.android.notifications

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Runs the real database enqueue policy across worker completion, failure and cancellation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = Application::class)
class PushWakeWorkManagerTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManager
    private lateinit var store: PushTokenStore
    private val started = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val calls = AtomicInteger()
    private var firstResult: ListenableWorker.Result = ListenableWorker.Result.success()

    /** Uses a held worker behind the production request class so database state transitions remain real. */
    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("scheduler-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = PushTokenStore(prefs)
        val factory =
            object : WorkerFactory() {
                /** Replaces only the native operation, preserving WorkManager's actual worker lifecycle. */
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? {
                    if (workerClassName != PushWakeRecoveryWorker::class.java.name) return null
                    return object : CoroutineWorker(appContext, workerParameters) {
                        /** Holds the first fetch across a burst and acknowledges only its captured marker. */
                        override suspend fun doWork(): Result {
                            val generation = store.pendingPushWakeCatchUpGeneration()
                            if (calls.incrementAndGet() == 1) {
                                started.complete(Unit)
                                release.await()
                                if (firstResult != Result.success()) return firstResult
                            }
                            store.clearPendingPushWakeCatchUp(generation)
                            return Result.success()
                        }
                    }
                }
            }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
        manager = WorkManager.getInstance(context)
    }

    /** Ensures a failed assertion cannot leave a held worker alive for the next scenario. */
    @After
    fun tearDown() {
        release.complete(Unit)
        manager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    /** A real RUNNING prerequisite has at most one BLOCKED successor across 100 callbacks. */
    @Test
    fun burstDuringRunningWorkerCommitsOneSuccessor() =
        runBlocking {
            store.recordPendingPushWakeCatchUp()
            schedule()
            runQueued()
            withTimeout(5_000) { started.await() }
            repeat(100) {
                store.recordPendingPushWakeCatchUp()
                schedule()
            }
            assertEquals(2, infos().count { !it.state.isFinished })
            assertEquals(1, infos().count { it.state == WorkInfo.State.RUNNING })
            assertEquals(1, infos().count { it.state == WorkInfo.State.BLOCKED })
            release.complete(Unit)
            awaitState { infos().none { it.state == WorkInfo.State.RUNNING } }
            runQueued()
            awaitState { infos().all { it.state.isFinished } }
            assertEquals(2, calls.get())
            assertFalse(store.pushWakeCatchUpPending())
        }

    /** A callback after terminal completion creates new work instead of being swallowed by KEEP. */
    @Test
    fun callbackAfterTerminalCommitRunsAgain() =
        runBlocking {
            release.complete(Unit)
            store.recordPendingPushWakeCatchUp()
            schedule()
            runQueued()
            awaitState { infos().all { it.state.isFinished } }
            store.recordPendingPushWakeCatchUp()
            schedule()
            runQueued()
            awaitState { infos().all { it.state.isFinished } }
            assertEquals(2, calls.get())
            assertFalse(store.pushWakeCatchUpPending())
        }

    /** A failed prerequisite is replaced on reconciliation and cannot strand a permanently blocked leaf. */
    @Test
    fun failedChainReconcilesToRunnableWork() =
        runBlocking {
            firstResult = ListenableWorker.Result.failure()
            store.recordPendingPushWakeCatchUp()
            schedule()
            runQueued()
            withTimeout(5_000) { started.await() }
            store.recordPendingPushWakeCatchUp()
            schedule()
            release.complete(Unit)
            awaitState { infos().all { it.state.isFinished } }
            assertTrue(store.pushWakeCatchUpPending())
            schedule()
            runQueued()
            awaitState { infos().all { it.state.isFinished } }
            assertFalse(store.pushWakeCatchUpPending())
            assertTrue(infos().none { it.state == WorkInfo.State.BLOCKED })
        }

    /** Cancellation preserves the marker and a later lifecycle trigger creates a fresh runnable leaf. */
    @Test
    fun cancelledChainReconciles() =
        runBlocking {
            store.recordPendingPushWakeCatchUp()
            schedule()
            manager.cancelUniqueWork(PushWakeRecoveryScheduler.WORK_NAME).result.get()
            release.complete(Unit)
            schedule()
            runQueued()
            awaitState { infos().all { it.state.isFinished } }
            assertFalse(store.pushWakeCatchUpPending())
        }

    /** Exercises the production serialized query/enqueue transaction against the real work database. */
    private fun schedule() {
        assertTrue(
            PushWakeRecoveryScheduler.schedule(store, { infos().map { it.state } }) { delay ->
                manager
                    .enqueueUniqueWork(
                        PushWakeRecoveryScheduler.WORK_NAME,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        pushWakeWorkRequest(false, 30, delay),
                    ).result
                    .get()
            },
        )
    }

    /** Reads fresh database state rather than assuming the enqueue operation made the worker run. */
    private fun infos(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(PushWakeRecoveryScheduler.WORK_NAME).get()

    /** Supplies the connected-network constraint only to currently runnable work. */
    private fun runQueued() {
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        infos().filter { it.state == WorkInfo.State.ENQUEUED }.forEach { driver.setAllConstraintsMet(it.id) }
    }

    /** Bounds asynchronous database settlement without conflating it with native work cancellation. */
    private suspend fun awaitState(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) kotlinx.coroutines.delay(10) }
    }
}
