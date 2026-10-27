package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerSendMotionDurationScaleTest {
    @Test
    fun twoTimesScaleKeepsTheCopyUntilTheScaledTweenAndLandingFinish() = scaledFlight(2f)

    @Test
    fun fiveTimesScaleKeepsTheCopyUntilTheScaledTweenAndLandingFinish() = scaledFlight(5f)

    @Test
    fun zeroScaleSettlesWithoutAnAnimatedFlight() = scaledFlight(0f)

    @Test
    fun missingTargetStillExpiresWithin80MillisecondsAtFiveTimesScale() =
        runTest {
            val progress = Animatable(0f)
            withContext(FrameClock(testScheduler) + DurationScale(5f)) {
                ComposerSendFlightTiming.run(progress, { false }, { false })
            }
            assertEquals(80L, testScheduler.currentTime)
            assertEquals(0f, progress.value)
        }

    @Test
    fun lostDestinationCancelsTheScaledFlightInsteadOfExtendingItsDeadline() =
        runTest {
            val progress = Animatable(0f)
            val error =
                runCatching {
                    withContext(FrameClock(testScheduler) + DurationScale(5f)) {
                        ComposerSendFlightTiming.run(progress, { testScheduler.currentTime < 96L }, { false })
                    }
                }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertEquals("Send presentation invalidated", error?.message)
            assertTrue(progress.value < 1f)
            assertTrue(testScheduler.currentTime <= 112L)
        }

    @Test
    fun changedSystemScaleCancelsRatherThanReusingAnObsoleteDeadline() =
        runTest {
            val scale = DurationScale(2f)
            val progress = Animatable(0f)
            val error =
                runCatching {
                    withContext(FrameClock(testScheduler) + scale) {
                        ComposerSendFlightTiming.run(
                            progress,
                            {
                                if (testScheduler.currentTime >= 96L) scale.scaleFactor = 5f
                                true
                            },
                            { false },
                        )
                    }
                }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertTrue(progress.value < 1f)
        }

    @Test
    fun nonfiniteNegativeAndExtremeScalesFallBackBeforeAnyFrameOrAnimation() =
        runTest {
            for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, Float.MAX_VALUE)) {
                val progress = Animatable(0f)
                withContext(FrameClock(testScheduler) + DurationScale(scale)) {
                    ComposerSendFlightTiming.run(progress, { error("Invalid scale must not measure") }, { false })
                }
                assertEquals(0f, progress.value)
                assertNull(ComposerSendFlightTiming.timeoutMillis(scale))
            }
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun deadlinePreservesNormalOverheadAndRoundsFractionalScaledDurationsUp() {
        assertEquals(360L, ComposerSendFlightTiming.timeoutMillis(1f))
        assertEquals(640L, ComposerSendFlightTiming.timeoutMillis(2f))
        assertEquals(1480L, ComposerSendFlightTiming.timeoutMillis(5f))
        assertEquals(80L, ComposerSendFlightTiming.timeoutMillis(0f))
        assertEquals(81L, ComposerSendFlightTiming.timeoutMillis(0.001f))
    }

    @Test
    fun numericalCompletionStillWaitsForTheActualPaintedLandingSignal() =
        runTest {
            val progress = Animatable(0f)
            val waiting = mutableListOf<Long>()
            withContext(FrameClock(testScheduler) + DurationScale(1f)) {
                ComposerSendFlightTiming.run(progress, { true }) {
                    if (progress.value == 1f) waiting += testScheduler.currentTime
                    progress.value == 1f && testScheduler.currentTime >= 336L
                }
            }
            assertTrue("A numeric p1 is not a painted landing", waiting.any { it < 336L })
            assertEquals(336L, testScheduler.currentTime)
        }

    private fun scaledFlight(scale: Float) =
        runTest {
            val progress = Animatable(0f)
            val samples = mutableListOf<Pair<Long, Float>>()
            val error =
                runCatching {
                    withContext(FrameClock(testScheduler) + DurationScale(scale)) {
                        ComposerSendFlightTiming.run(
                            progress,
                            {
                                samples += testScheduler.currentTime to progress.value
                                true
                            },
                            { progress.value == 1f && testScheduler.currentTime >= (280 * scale).toLong() + 32L },
                        )
                    }
                }.exceptionOrNull()
            if (error != null) assertEquals("Accepted copy handed off", error.message)
            assertEquals(1f, progress.value)
            assertTrue(testScheduler.currentTime <= checkNotNull(ComposerSendFlightTiming.timeoutMillis(scale)))
            if (scale > 1f) {
                assertTrue("The old 360 ms timeout must not end a scaled flight", testScheduler.currentTime > 360L)
                assertTrue(samples.any { (time, value) -> time >= 360L && value in 0.01f..0.99f })
            }
        }

    private class DurationScale(
        override var scaleFactor: Float,
    ) : MotionDurationScale

    private class FrameClock(
        private val scheduler: TestCoroutineScheduler,
    ) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16L)
            return onFrame(scheduler.currentTime * 1_000_000L)
        }
    }
}
