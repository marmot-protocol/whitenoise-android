package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NotificationCatchUpWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The coordinator opens the catch-up window for exactly the span of executed native work. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountCatchUpWindowTest {
    private val key = AccountCatchUpKey(accountRef = "personal", runtimeGeneration = 1, networkGeneration = 1L)

    /** The window opens when the request starts and stays current through the tail after it settles. */
    @Test
    fun executedCatchUpOpensAndClosesTheWindow() =
        runTest {
            var elapsed = 0L
            val window = NotificationCatchUpWindow(tailMs = 5_000L, clock = { elapsed })
            val coordinator = AccountCatchUpCoordinator(this, window)
            val release = CompletableDeferred<Unit>()

            assertNull(window.currentGeneration())
            val result =
                coordinator.launch(key) {
                    release.await()
                    true
                }
            runCurrent()
            assertEquals(1L, window.currentGeneration())

            release.complete(Unit)
            result.await()
            runCurrent()
            assertEquals("the tail keeps posts already in flight inside the cohort", 1L, window.currentGeneration())
            elapsed += 5_001L
            assertNull(window.currentGeneration())
        }

    /** A queued successor that starts right after its predecessor settles extends the same cohort. */
    @Test
    fun coalescedSuccessorSharesTheCohort() =
        runTest {
            var elapsed = 0L
            val window = NotificationCatchUpWindow(tailMs = 5_000L, clock = { elapsed })
            val coordinator = AccountCatchUpCoordinator(this, window)
            val releaseFirst = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()

            val first =
                coordinator.launch(key) {
                    releaseFirst.await()
                    true
                }
            runCurrent()
            val second =
                coordinator.launch(key.copy(networkGeneration = 2L)) {
                    releaseSecond.await()
                    true
                }
            releaseFirst.complete(Unit)
            first.await()
            runCurrent()
            assertEquals(1L, window.currentGeneration())
            releaseSecond.complete(Unit)
            second.await()
            runCurrent()
            assertNotNull(window.currentGeneration())
            assertEquals(1L, window.currentGeneration())
        }

    /** A failed catch-up settles the window like a successful one, so it cannot suppress alerts for long. */
    @Test
    fun failedCatchUpStillClosesTheWindow() =
        runTest {
            var elapsed = 0L
            val window = NotificationCatchUpWindow(tailMs = 5_000L, clock = { elapsed })
            val coordinator = AccountCatchUpCoordinator(this, window)

            val result = coordinator.launch(key) { false }
            runCurrent()
            assertEquals(AccountCatchUpOutcome.Failed, result.await().outcome)
            runCurrent()
            elapsed += 5_001L
            assertNull(window.currentGeneration())
        }
}
