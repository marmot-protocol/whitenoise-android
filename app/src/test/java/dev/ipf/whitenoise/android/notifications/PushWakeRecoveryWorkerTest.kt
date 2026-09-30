package dev.ipf.whitenoise.android.notifications

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** Tests actual worker policy with controlled runtime and durable scheduling boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushWakeRecoveryWorkerTest {
    private lateinit var store: PushTokenStore
    private var allowed = true
    private var runs = 0
    private var schedules = 0

    /** Clears only fixture bookkeeping before each worker invocation. */
    @Before
    fun setUp() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("worker-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = PushTokenStore(prefs)
    }

    /** Idle workers never construct the runtime or touch the network. */
    @Test
    fun noPendingWorkDoesNotBootstrap() =
        runBlocking {
            allowed = false
            val result =
                executePushWakeWork(
                    runAttemptCount = 0,
                    loadStore = { store },
                    recoveryAllowed = { error("idle bootstrap") },
                    recover = { error("idle native") },
                    schedule = { error("idle schedule") },
                )
            assertEquals(Result.success(), result)
        }

    /** Suppressed recovery preserves its generation without spending the finite budget. */
    @Test
    fun destructiveBoundaryWaitsForLifecycle() =
        runBlocking {
            store.recordPendingPushWakeCatchUp()
            allowed = false
            assertEquals(Result.success(), execute { runs++ })
            assertEquals(0, runs)
            assertEquals(0, store.pushWakeAttempts())
            assertTrue(store.pushWakeCatchUpPending())
        }

    /** Quiet success acknowledges once and never retries merely because no notification was posted. */
    @Test
    fun quietSuccessHasNoSuccessor() =
        runBlocking {
            store.recordPendingPushWakeCatchUp()
            val result = execute { store.clearPendingPushWakeCatchUp(store.pendingPushWakeCatchUpGeneration()) }
            assertEquals(Result.success(), result)
            assertEquals(0, schedules)
        }

    /** A wake after acknowledgement but before doWork returns is durably handed to a successor. */
    @Test
    fun completionBoundaryWakeGetsSuccessor() =
        runBlocking {
            store.recordPendingPushWakeCatchUp()
            assertEquals(
                Result.success(),
                execute {
                    store.clearPendingPushWakeCatchUp(store.pendingPushWakeCatchUpGeneration())
                    store.recordPendingPushWakeCatchUp()
                },
            )
            assertEquals(1, schedules)
            assertTrue(store.pushWakeCatchUpPending())
        }

    /** Repeated storage failures remain within a WorkSpec retry limit and cannot grow an append chain. */
    @Test
    fun bootstrapAndStorageErrorsCannotAppendForever() =
        runBlocking {
            store.recordPendingPushWakeCatchUp()
            repeat(4) { attempt -> assertEquals(Result.retry(), execute(attempt) { error("failure") }) }
            assertEquals(Result.success(), execute(4) { error("must not run") })
            assertEquals(0, schedules)
            assertTrue(store.pushWakeCatchUpPending())
        }

    /** Cold AppState construction participates in the same finite retry boundary as native recovery. */
    @Test
    fun coldRuntimeInitializationFailureRetriesAndPreservesGeneration() =
        runBlocking {
            store.recordPendingPushWakeCatchUp()
            val generation = store.pendingPushWakeCatchUpGeneration()
            val result =
                executePushWakeWork(
                    runAttemptCount = 0,
                    loadStore = { store },
                    recoveryAllowed = { throw IOException("runtime construction temporarily unavailable") },
                    recover = { error("must not reach native recovery") },
                    schedule = { error("the current worker owns this retry") },
                )

            assertEquals(Result.retry(), result)
            assertEquals(generation, store.pendingPushWakeCatchUpGeneration())
            assertEquals(0, schedules)
        }

    /** Store construction failures retry the same WorkSpec four times without appending successors. */
    @Test
    fun storeInitializationFailureHasIndependentFiniteBound() =
        runBlocking {
            store.recordPendingPushWakeCatchUp()
            val generation = store.pendingPushWakeCatchUpGeneration()
            repeat(PUSH_WAKE_MAX_ATTEMPTS) { attempt ->
                assertEquals(
                    Result.retry(),
                    executePushWakeWork(
                        runAttemptCount = attempt,
                        loadStore = { throw IOException("keystore temporarily unavailable") },
                        recoveryAllowed = { error("must not evaluate eligibility") },
                        recover = { error("must not reach native recovery") },
                        schedule = { error("must not append a successor") },
                    ),
                )
            }
            assertEquals(
                Result.success(),
                executePushWakeWork(
                    runAttemptCount = PUSH_WAKE_MAX_ATTEMPTS,
                    loadStore = { error("exhaustion must not reopen storage") },
                    recoveryAllowed = { error("exhaustion must not evaluate eligibility") },
                    recover = { error("exhaustion must not recover") },
                    schedule = { error("exhaustion must not append") },
                ),
            )
            assertEquals(generation, store.pendingPushWakeCatchUpGeneration())
            assertEquals(0, schedules)
        }

    /** Fatal VM errors are never converted into an ordinary background retry. */
    @Test(expected = AssertionError::class)
    fun fatalInitializationErrorPropagates() =
        runBlocking {
            executePushWakeWork(
                runAttemptCount = 0,
                loadStore = { throw AssertionError("fatal") },
                recoveryAllowed = { true },
                recover = { },
                schedule = { true },
            )
            Unit
        }

    /** Cancellation is returned to WorkManager, never turned into success or another retry owner. */
    @Test(expected = CancellationException::class)
    fun cancellationPropagates() =
        runBlocking {
            store.recordPendingPushWakeCatchUp()
            execute { throw CancellationException("cancelled") }
            Unit
        }

    /** Invokes the production worker contract with one owned native operation. */
    private suspend fun execute(
        attempt: Int = 0,
        recover: suspend () -> Unit,
    ): Result =
        executePushWakeWork(attempt, { store }, { allowed }, recover, {
            schedules++
            true
        }, { 1_000L })
}
