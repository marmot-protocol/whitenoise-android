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
    fun coldStartSampleReplaysWhenItsRuntimeIsPublished() {
        val samples = mutableListOf<Sample>()
        val recorder = HostPerformanceRecorder(generation = { 4 }, nowMs = { 10L })

        recorder.record(
            HostPerformanceOperationFfi.WINDOW_INIT,
            durationMs = 7L,
            outcome = HostPerformanceOutcomeFfi.SUCCESS,
        )
        assertTrue(samples.isEmpty())

        recorder.publishEmitter(owner = Any(), ownerGeneration = 4, emitter = samples.emitter())

        assertEquals(
            listOf(Sample(HostPerformanceOperationFfi.WINDOW_INIT, 7L, HostPerformanceOutcomeFfi.SUCCESS)),
            samples,
        )
    }

    @Test
    fun coldStartAttemptCompletesAfterItsFirstRuntimePublication() {
        var now = 20L
        val firstRuntimeSamples = mutableListOf<Sample>()
        val replacementSamples = mutableListOf<Sample>()
        val recorder = HostPerformanceRecorder(generation = { 2 }, nowMs = { now })
        val attempt = recorder.begin(HostPerformanceOperationFfi.FOREGROUND_LOCAL_READY)
        val firstOwner = Any()

        recorder.publishEmitter(firstOwner, ownerGeneration = 2, emitter = firstRuntimeSamples.emitter())
        recorder.clearEmitter(firstOwner)
        recorder.publishEmitter(Any(), ownerGeneration = 2, emitter = replacementSamples.emitter())
        now = 35L
        attempt.success()

        assertEquals(
            listOf(
                Sample(
                    HostPerformanceOperationFfi.FOREGROUND_LOCAL_READY,
                    15L,
                    HostPerformanceOutcomeFfi.SUCCESS,
                ),
            ),
            firstRuntimeSamples,
        )
        assertTrue(replacementSamples.isEmpty())
    }

    @Test
    fun unpublishedStaleGenerationIsNotReplayedIntoReplacementRuntime() {
        var generation = 1
        val replacementSamples = mutableListOf<Sample>()
        val recorder = HostPerformanceRecorder(generation = { generation }, nowMs = { 0L })

        recorder.record(
            HostPerformanceOperationFfi.SPLASH_READY,
            durationMs = 5L,
            outcome = HostPerformanceOutcomeFfi.SUCCESS,
        )
        generation = 2
        recorder.publishEmitter(Any(), ownerGeneration = 2, emitter = replacementSamples.emitter())

        assertTrue(replacementSamples.isEmpty())
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

    @Test
    fun warmForegroundReadySettlesWithoutAComposeCallback() {
        var now = 10L
        val samples = mutableListOf<Sample>()
        val slot = HostPerformanceAttemptSlot()
        slot.replace(
            recorder(now = { now }, samples = samples)
                .begin(HostPerformanceOperationFfi.FOREGROUND_LOCAL_READY),
        )

        now = 14L
        assertTrue(slot.successIf(ready = true))

        assertEquals(
            listOf(
                Sample(
                    HostPerformanceOperationFfi.FOREGROUND_LOCAL_READY,
                    4L,
                    HostPerformanceOutcomeFfi.SUCCESS,
                ),
            ),
            samples,
        )
    }

    @Test
    fun coldForegroundWaitsForLocalReadiness() {
        var now = 100L
        val samples = mutableListOf<Sample>()
        val slot = HostPerformanceAttemptSlot()
        slot.replace(
            recorder(now = { now }, samples = samples)
                .begin(HostPerformanceOperationFfi.FOREGROUND_LOCAL_READY),
        )

        assertFalse(slot.successIf(ready = false))
        assertTrue(samples.isEmpty())

        now = 145L
        assertTrue(slot.success())
        assertEquals(45L, samples.single().durationMs)
    }

    @Test
    fun outboundVisibilityWaitsForTheClaimedRenderedBatch() {
        var now = 20L
        val samples = mutableListOf<Sample>()
        val registry = HostPerformanceAttemptRegistry()
        registry.register(
            "optimistic-1",
            recorder(now = { now }, samples = samples).begin(HostPerformanceOperationFfi.OUTBOUND_MESSAGE_VISIBLE),
        )

        val rendered = registry.claimAll()
        assertTrue(samples.isEmpty())
        assertTrue(registry.claimAll().isEmpty)

        now = 35L
        rendered.success()
        assertEquals(
            Sample(HostPerformanceOperationFfi.OUTBOUND_MESSAGE_VISIBLE, 15L, HostPerformanceOutcomeFfi.SUCCESS),
            samples.single(),
        )
    }

    @Test
    fun inboundReplacementCancelsTheSupersededFrameOwner() {
        var now = 0L
        val samples = mutableListOf<Sample>()
        val telemetry = recorder(now = { now }, samples = samples)
        val slot = HostPerformanceAttemptSlot()
        slot.replace(telemetry.begin(HostPerformanceOperationFfi.INBOUND_MESSAGE_VISIBLE))

        now = 8L
        slot.replace(telemetry.begin(HostPerformanceOperationFfi.INBOUND_MESSAGE_VISIBLE))
        now = 13L
        slot.success()

        assertEquals(
            listOf(
                Sample(HostPerformanceOperationFfi.INBOUND_MESSAGE_VISIBLE, 8L, HostPerformanceOutcomeFfi.CANCELLED),
                Sample(HostPerformanceOperationFfi.INBOUND_MESSAGE_VISIBLE, 5L, HostPerformanceOutcomeFfi.SUCCESS),
            ),
            samples,
        )
    }

    @Test
    fun settingsSaveUsesTheDurableCommitResult() {
        val samples = mutableListOf<Sample>()
        val telemetry = recorder(samples = samples)

        completeHostPreferenceCommit(telemetry.begin(HostPerformanceOperationFfi.SETTINGS_SAVE)) { false }

        assertEquals(HostPerformanceOutcomeFfi.FAILURE, samples.single().outcome)
    }

    private fun recorder(
        generation: () -> Int = { 1 },
        now: () -> Long = { 0L },
        samples: MutableList<Sample>,
    ) = HostPerformanceRecorder(generation = generation, nowMs = now).also { recorder ->
        recorder.publishEmitter(owner = Any(), ownerGeneration = generation(), emitter = samples.emitter())
    }

    private fun MutableList<Sample>.emitter() =
        HostPerformanceEmitter { operation, durationMs, outcome ->
            this += Sample(operation, durationMs, outcome)
        }

    private data class Sample(
        val operation: HostPerformanceOperationFfi,
        val durationMs: Long,
        val outcome: HostPerformanceOutcomeFfi,
    )
}
