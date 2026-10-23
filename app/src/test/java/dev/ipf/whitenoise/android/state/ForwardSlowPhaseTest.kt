package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForwardSlowPhaseTest {
    /** A phase that makes no progress for a whole window is marked slow. */
    @Test
    fun aPhaseWithoutProgressIsMarkedSlowAfterOneWindow() =
        runTest {
            var marks = 0
            val finish = CompletableDeferred<Unit>()
            launch { withSlowPhaseNotice(markSlow = { marks += 1 }, progress = { 0 }) { finish.await() } }

            advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS - 1)
            runCurrent()
            assertEquals(0, marks)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, marks)
            finish.complete(Unit)
        }

    /** Progress inside a window restarts it, so a long phase that keeps moving is never called slow. */
    @Test
    fun progressInsideAWindowRestartsIt() =
        runTest {
            var marks = 0
            var uploaded = 0
            val finish = CompletableDeferred<Unit>()
            launch { withSlowPhaseNotice(markSlow = { marks += 1 }, progress = { uploaded }) { finish.await() } }

            repeat(4) {
                advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS / 2)
                uploaded += 1
                advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS / 2)
                runCurrent()
            }
            assertEquals(0, marks)

            advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS)
            runCurrent()
            assertEquals(1, marks)
            finish.complete(Unit)
        }

    /** The timer ends with its phase, so a finished phase is never marked slow afterwards. */
    @Test
    fun theTimerStopsWhenThePhaseEnds() =
        runTest {
            var marks = 0
            withSlowPhaseNotice(markSlow = { marks += 1 }, progress = { 0 }) { delay(1) }

            advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS * 3)
            runCurrent()
            assertEquals(0, marks)
        }
}
