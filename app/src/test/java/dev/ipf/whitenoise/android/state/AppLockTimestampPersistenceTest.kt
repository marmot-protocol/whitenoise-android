package dev.ipf.whitenoise.android.state

import android.content.Context
import android.hardware.biometrics.BiometricManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLockTimestampPersistenceTest {
    /** A blocked real worker must leave authentication completion and the live grace period usable. */
    @Test
    fun blockedTimestampStorageDoesNotHoldUnlockOrItsLiveGracePeriod() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val caller = Thread.currentThread()
            val writerThreads = CopyOnWriteArrayList<Thread>()
            val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val state =
                state(io) {
                    writerThreads += Thread.currentThread()
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    finished.countDown()
                }
            try {
                state.requestAppUnlock()
                assertTrue(state.appLockScreenVisible)
                state.appUnlockSessions.clear()
                state.markAppUnlockSucceeded(1_000L, dismissRetainedVisibleConversation = false)
                assertFalse(state.appLockScreenVisible)
                state.maybeShowAppLockForForeground(1_001L)
                assertFalse(state.appLockScreenVisible)
                runCurrent()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertNotEquals(caller, writerThreads.single())
                assertEquals(1L, release.count)
            } finally {
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                state.mutationsScope.cancel()
                state.mutationsScope.coroutineContext[Job]!!.join()
                io.close()
                Dispatchers.resetMain()
            }
        }

    /** Persist callback order rather than sorting by timestamps or worker completion. */
    @Test
    fun lifecycleWritesKeepInvocationOrderEvenWhenTheClockMovesBackwards() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val firstStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val firstFinished = CountDownLatch(1)
            val secondFinished = CountDownLatch(1)
            val writes = CopyOnWriteArrayList<Long>()
            val io = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
            val state =
                state(io) { value ->
                    if (value == 2_000L) {
                        firstStarted.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                    writes += value
                    if (value == 2_000L) firstFinished.countDown() else secondFinished.countDown()
                }
            try {
                state.markAppUnlockSucceeded(2_000L, dismissRetainedVisibleConversation = false)
                runCurrent()
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
                state.markAppUnlockSucceeded(1_000L, dismissRetainedVisibleConversation = false)
                runCurrent()
                assertTrue(writes.isEmpty())
                release.countDown()
                assertTrue(firstFinished.await(5, TimeUnit.SECONDS))
                // The IO continuation must return to Main before the waiting write can acquire the mutex.
                state.mutationsScope.coroutineContext[Job]!!
                    .children
                    .toList()
                    .joinAll()
                assertEquals(0L, secondFinished.count)
                assertEquals(listOf(2_000L, 1_000L), writes)
            } finally {
                release.countDown()
                state.mutationsScope.cancel()
                state.mutationsScope.coroutineContext[Job]!!.join()
                io.close()
                Dispatchers.resetMain()
            }
        }

    /** Cancellation drops waiting writes while allowing the already-running synchronous call to finish. */
    @Test
    fun cancelledProcessScopeDoesNotStartAQueuedTimestampWrite() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val writes = CopyOnWriteArrayList<Long>()
            val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val state =
                state(io) { value ->
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    writes += value
                    finished.countDown()
                }
            try {
                state.markAppUnlockSucceeded(1_000L, dismissRetainedVisibleConversation = false)
                runCurrent()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                state.markAppUnlockSucceeded(2_000L, dismissRetainedVisibleConversation = false)
                runCurrent()
                state.mutationsScope.cancel()
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                state.mutationsScope.coroutineContext[Job]!!.join()
                assertEquals(listOf(1_000L), writes)
            } finally {
                release.countDown()
                state.mutationsScope.cancel()
                state.mutationsScope.coroutineContext[Job]!!.join()
                io.close()
                Dispatchers.resetMain()
            }
        }

    /** Drive actual background callbacks through the existing locked and authenticated branches. */
    @Test
    fun backgroundLifecycleKeepsTheLockedGateAndEnqueuesOnlyAnUnlockedBaseline() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val writes = mutableListOf<Long>()
            val state = state(dispatcher) { writes += it }
            try {
                state.requestAppUnlock()
                state.setAppInForeground(false)
                runCurrent()
                assertTrue(writes.isEmpty())
                state.appUnlockSessions.clear()
                state.markAppUnlockSucceeded(1_000L, dismissRetainedVisibleConversation = false)
                runCurrent()
                assertEquals(listOf(1_000L), writes)
                val before = System.currentTimeMillis()
                state.setAppInForeground(false)
                val after = System.currentTimeMillis()
                runCurrent()
                assertEquals(2, writes.size)
                assertTrue(writes.last() in before..after)
            } finally {
                state.mutationsScope.cancel()
                state.mutationsScope.coroutineContext[Job]!!.join()
                Dispatchers.resetMain()
            }
        }

    /** Build credentialed host state with a controlled blocking sink and I/O dispatcher. */
    private fun state(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        write: (Long) -> Unit,
    ): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadow
            .extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java))
            .setCanAuthenticate(true)
        val preferences = context.getSharedPreferences("app-lock-timestamp-test", Context.MODE_PRIVATE)
        preferences
            .edit()
            .clear()
            .putBoolean("require_app_unlock", true)
            .putString("app_lock_delay", "1m")
            .commit()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { it },
            accounts = emptyList(),
            activeAccountRef = null,
            preferences = preferences,
            appLockTimestampWrite = write,
            appLockPersistenceDispatcher = dispatcher,
        )
    }
}
