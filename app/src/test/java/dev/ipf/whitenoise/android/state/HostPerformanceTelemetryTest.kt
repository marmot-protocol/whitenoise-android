package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPerformanceTelemetryTest {
    @Test
    fun firstTerminalOutcomeWins() {
        var now = 100L
        val samples = mutableListOf<Sample>()
        val recorder = recorder(now = { now }, samples = samples)

        val attempt = recorder.begin(HostPerformanceOperationFfi.MEDIA_LOAD)
        now = 145L

        assertTrue(attempt.success())
        assertFalse(attempt.failure())
        assertEquals(
            listOf(Sample(HostPerformanceOperationFfi.MEDIA_LOAD, 45L, HostPerformanceOutcomeFfi.SUCCESS)),
            samples,
        )
    }

    @Test
    fun staleGenerationCannotReportSuccessIntoReplacementRuntime() {
        var generation = 7
        var now = 10L
        val samples = mutableListOf<Sample>()
        val recorder = recorder(generation = { generation }, now = { now }, samples = samples)
        val attempt = recorder.begin(HostPerformanceOperationFfi.ACCOUNT_SWITCH)

        generation = 8
        now = 30L
        attempt.success()

        assertEquals(
            listOf(Sample(HostPerformanceOperationFfi.ACCOUNT_SWITCH, 20L, HostPerformanceOutcomeFfi.CANCELLED)),
            samples,
        )
    }

    @Test
    fun cancellationIsTerminalAndRethrown() =
        runTest {
            val samples = mutableListOf<Sample>()
            val recorder = recorder(samples = samples)

            val thrown =
                runCatching {
                    recorder.measure(HostPerformanceOperationFfi.TIMELINE_PAGE) {
                        throw CancellationException("test")
                    }
                }.exceptionOrNull()

            assertTrue(thrown is CancellationException)
            assertEquals(HostPerformanceOutcomeFfi.CANCELLED, samples.single().outcome)
        }

    @Test
    fun androidCatalogContainsEverySharedStageAndNoLinuxStage() {
        val expected =
            HostPerformanceOperationFfi.entries.filterNot { it.name.startsWith("LINUX_") }.toSet()

        assertEquals(expected, ANDROID_HOST_PERFORMANCE_OPERATIONS)
        assertEquals(34, expected.size)
    }

    private fun recorder(
        generation: () -> Int = { 1 },
        now: () -> Long = { 0L },
        samples: MutableList<Sample>,
    ) = HostPerformanceRecorder(
        generation = generation,
        emitter = {
            HostPerformanceEmitter { operation, durationMs, outcome ->
                samples += Sample(operation, durationMs, outcome)
            }
        },
        nowMs = now,
    )

    private data class Sample(
        val operation: HostPerformanceOperationFfi,
        val durationMs: Long,
        val outcome: HostPerformanceOutcomeFfi,
    )
}
