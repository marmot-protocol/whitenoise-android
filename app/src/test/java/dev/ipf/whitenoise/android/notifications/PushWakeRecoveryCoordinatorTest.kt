package dev.ipf.whitenoise.android.notifications

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Proves dispatch and durable scheduling decisions without requiring a live push server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushWakeRecoveryCoordinatorTest {
    private lateinit var store: PushTokenStore
    private var serviceStarts = 0
    private val scheduled = mutableListOf<Boolean>()
    private var serviceAccepted = false
    private var ownerAccepted = false
    private var scheduleAccepted = true

    /** Each scenario has independent platform bookkeeping. */
    @Before
    fun setUp() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("wake-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = PushTokenStore(prefs)
    }

    /** A rejected high-priority start must leave a confirmed expedited scheduling request. */
    @Test
    fun rejectedServiceSchedulesRecovery() =
        runBlocking {
            assertEquals(PushWakeDispatch.Scheduled, coordinator().receive(PushWakePriority.High))
            assertEquals(1, serviceStarts)
            assertEquals(listOf(true), scheduled)
            assertTrue(store.pushWakeCatchUpPending())
        }

    /** Only received priority can grant the high-priority route. */
    @Test
    fun downgradedAndUnknownPrioritiesUseRegularWork() =
        runBlocking {
            coordinator().receive(PushWakePriority.Normal, PushWakePriority.High)
            coordinator().receive(PushWakePriority.Unknown, PushWakePriority.High)
            assertEquals(0, serviceStarts)
            assertEquals(listOf(false, false), scheduled)
        }

    /** Backlog deletion requests native recovery even without any message payload. */
    @Test
    fun deletedBacklogUsesTheSameDurableGeneration() =
        runBlocking {
            coordinator().receive(PushWakePriority.High, deleted = true)
            assertEquals(0, serviceStarts)
            assertEquals(listOf(false), scheduled)
            assertTrue(store.pushWakeCatchUpPending())
        }

    /** An explicitly accepting runtime prevents redundant platform starts. */
    @Test
    fun existingOwnerAcceptsResponsibility() =
        runBlocking {
            ownerAccepted = true
            assertEquals(PushWakeDispatch.Owner, coordinator().receive(PushWakePriority.High))
            assertEquals(0, serviceStarts)
            assertTrue(scheduled.isEmpty())
        }

    /** Keep the eligible service path without scheduling a competing worker. */
    @Test
    fun acceptedServiceKeepsHighPriorityRoute() =
        runBlocking {
            serviceAccepted = true
            assertEquals(PushWakeDispatch.Service, coordinator().receive(PushWakePriority.High))
            assertTrue(scheduled.isEmpty())
        }

    /** Failed enqueue retains the obligation and is never labelled durable success. */
    @Test
    fun enqueueFailureIsExplicit() =
        runBlocking {
            scheduleAccepted = false
            assertEquals(PushWakeDispatch.ScheduleFailed, coordinator().receive(PushWakePriority.Normal))
            assertTrue(store.pushWakeCatchUpPending())
        }

    /** Platform scheduling excludes API-30 foreground emulation and supports quota downgrade on newer devices. */
    @Test
    fun requestConstraintsAndApi30Fallback() {
        val legacy = pushWakeWorkRequest(true, 30).workSpec
        val modern = pushWakeWorkRequest(true, 34).workSpec
        assertFalse(legacy.expedited)
        assertTrue(modern.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, modern.outOfQuotaPolicy)
        assertEquals(NetworkType.CONNECTED, modern.constraints.requiredNetworkType)
        assertFalse(modern.constraints.requiresCharging())
        assertFalse(modern.constraints.requiresBatteryNotLow())
        assertEquals(BackoffPolicy.EXPONENTIAL, modern.backoffPolicy)
        assertEquals(30_000L, modern.backoffDelayDuration)
        assertFalse(pushWakeWorkRequest(true, 34, 30_000L).workSpec.expedited)
    }

    /** A finishing RUNNING worker receives one successor; 100 subsequent schedules reuse it. */
    @Test
    fun finishingWorkerAndBurstRetainOnlyOneSuccessor() {
        store.recordPendingPushWakeCatchUp()
        val states = mutableListOf(WorkInfo.State.RUNNING)
        var enqueues = 0
        repeat(100) {
            store.recordPendingPushWakeCatchUp()
            assertTrue(
                PushWakeRecoveryScheduler.schedule(store, { states }) {
                    enqueues++
                    states += WorkInfo.State.BLOCKED
                },
            )
        }
        assertEquals(1, enqueues)
        assertEquals(101L, store.pendingPushWakeCatchUpGeneration())
    }

    /** Neither a terminal prerequisite nor an absent marker leaves work permanently blocked or idle polling. */
    @Test
    fun terminalStatesRecoverAndIdleDoesNotSchedule() {
        var enqueues = 0
        assertTrue(PushWakeRecoveryScheduler.schedule(store, { error("idle must not query") }) { enqueues++ })
        store.recordPendingPushWakeCatchUp()
        for (state in listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
            assertTrue(PushWakeRecoveryScheduler.schedule(store, { listOf(state) }) { enqueues++ })
        }
        assertEquals(3, enqueues)
        assertFalse(PushWakeRecoveryScheduler.schedule(store, { emptyList() }) { error("disk failure") })
        assertTrue(store.pushWakeCatchUpPending())
    }

    /** Reopening the store after process death preserves budget and newer generations. */
    @Test
    fun retryBudgetSurvivesOwnersBurstsAndRestart() =
        runBlocking {
            var now = 1_000L
            store.recordPendingPushWakeCatchUp()
            repeat(4) {
                assertTrue(store.claimPushWakeAttempt(now) != null)
                assertTrue(store.deferPushWakeRetry(now))
                repeat(100) { coordinator(now).receive(PushWakePriority.High) }
                now += 240_000L
            }
            assertEquals(4, store.pushWakeAttempts())
            assertTrue(store.claimPushWakeAttempt(now) == null)
            val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("wake-test", Context.MODE_PRIVATE)
            val restarted = PushTokenStore(prefs)
            assertEquals(4, restarted.pushWakeAttempts())
            assertTrue(restarted.admitPushWakeEpisode(now))
            assertTrue(restarted.claimPushWakeAttempt(now) != null)
            assertEquals(1, restarted.pushWakeAttempts())
        }

    /** Constructs independent dispatch seams around the same durable episode. */
    private fun coordinator(now: Long = 1_000L) =
        PushWakeRecoveryCoordinator(
            store = store,
            acceptOwner = { ownerAccepted },
            startService = {
                serviceStarts++
                serviceAccepted
            },
            schedule = {
                scheduled += it
                scheduleAccepted
            },
            nowMs = { now },
        )
}
