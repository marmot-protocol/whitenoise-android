package dev.ipf.whitenoise.android.audio.tts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsEstimatedWordTickerTest {
    private data class Emitted(
        val utteranceId: String,
        val start: Int,
        val end: Int,
    )

    @Test
    fun replaysTheScheduleOnAnAbsoluteClock() =
        runTest {
            val ticker = tickerOnTestClock()
            val emitted = mutableListOf<Emitted>()

            ticker.start(
                utteranceId = "u1",
                words =
                    listOf(
                        TtsEstimatedWord(start = 0, end = 3, startMs = 0),
                        TtsEstimatedWord(start = 4, end = 8, startMs = 500),
                    ),
            ) { id, start, end ->
                emitted += Emitted(id, start, end)
                true
            }

            // Nothing before the lead-in has been absorbed.
            advanceTimeBy(60)
            runCurrent()
            assertEquals(emptyList<Emitted>(), emitted)

            advanceTimeBy(200)
            runCurrent()
            assertEquals(listOf(Emitted("u1", 0, 3)), emitted)

            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf(Emitted("u1", 0, 3), Emitted("u1", 4, 8)), emitted)

            ticker.shutdown()
        }

    @Test
    fun eachWordIsEmittedOnceNotOncePerTick() =
        runTest {
            val ticker = tickerOnTestClock()
            val emitted = mutableListOf<Emitted>()

            ticker.start(
                utteranceId = "u1",
                words = listOf(TtsEstimatedWord(start = 0, end = 3, startMs = 0)),
            ) { id, start, end ->
                emitted += Emitted(id, start, end)
                true
            }

            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(1, emitted.size)
            ticker.shutdown()
        }

    @Test
    fun emitReturningFalseStopsTheSchedule() =
        runTest {
            val ticker = tickerOnTestClock()
            var calls = 0

            ticker.start(
                utteranceId = "u1",
                words =
                    listOf(
                        TtsEstimatedWord(start = 0, end = 3, startMs = 0),
                        TtsEstimatedWord(start = 4, end = 8, startMs = 300),
                    ),
            ) { _, _, _ ->
                calls += 1
                false
            }

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(1, calls)
            ticker.shutdown()
        }

    @Test
    fun stopCancelsTheSchedule() =
        runTest {
            val ticker = tickerOnTestClock()
            val emitted = mutableListOf<Emitted>()

            ticker.start(
                utteranceId = "u1",
                words = listOf(TtsEstimatedWord(start = 0, end = 3, startMs = 400)),
            ) { id, start, end ->
                emitted += Emitted(id, start, end)
                true
            }
            ticker.stop()

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(emptyList<Emitted>(), emitted)
        }

    @Test
    fun aNewScheduleReplacesThePreviousOne() =
        runTest {
            val ticker = tickerOnTestClock()
            val emitted = mutableListOf<Emitted>()
            val record = { id: String, start: Int, end: Int ->
                emitted += Emitted(id, start, end)
                true
            }

            ticker.start("u1", listOf(TtsEstimatedWord(start = 0, end = 3, startMs = 800)), record)
            ticker.start("u2", listOf(TtsEstimatedWord(start = 0, end = 5, startMs = 0)), record)

            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(listOf(Emitted("u2", 0, 5)), emitted)
            ticker.shutdown()
        }

    @Test
    fun theScheduleEndsOnItsOwnPastTheGraceHorizon() =
        runTest {
            val ticker = tickerOnTestClock()

            ticker.start(
                utteranceId = "u1",
                words = listOf(TtsEstimatedWord(start = 0, end = 3, startMs = 0)),
            ) { _, _, _ -> true }

            advanceTimeBy(60_000)
            runCurrent()

            // The loop terminated: advancing further schedules no new work.
            assertTrue(testScheduler.currentTime >= 60_000)
            ticker.shutdown()
        }

    @Test
    fun anEmptyScheduleNeverTicks() =
        runTest {
            val ticker = tickerOnTestClock()
            var calls = 0

            ticker.start(utteranceId = "u1", words = emptyList()) { _, _, _ ->
                calls += 1
                true
            }

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(0, calls)
        }

    private fun kotlinx.coroutines.test.TestScope.tickerOnTestClock(): TtsEstimatedWordTicker =
        TtsEstimatedWordTicker(
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )
}
