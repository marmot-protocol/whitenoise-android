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
    /** Verifies a timing attempt emits only its first requested terminal outcome. */
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

    /** Verifies work owned by an old runtime is cancelled instead of succeeding in its replacement. */
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

    /** Verifies a completed startup sample waits for the matching runtime emitter. */
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

    /** Verifies an attempt started before bootstrap remains bound to the first published runtime. */
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

    /** Verifies samples from an unpublished generation are never replayed into a replacement runtime. */
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

    /** Verifies the bounded pre-runtime queue discards its oldest sample first. */
    @Test
    fun deferredSamplesDiscardTheOldestEntryAtTheBound() {
        val samples = mutableListOf<Sample>()
        val recorder = HostPerformanceRecorder(generation = { 1 }, nowMs = { 0L })

        repeat(MAX_DEFERRED_HOST_PERFORMANCE_EMITTERS + 1) { index ->
            recorder.record(
                HostPerformanceOperationFfi.WINDOW_INIT,
                durationMs = index.toLong(),
                outcome = HostPerformanceOutcomeFfi.SUCCESS,
            )
        }
        recorder.publishEmitter(Any(), ownerGeneration = 1, emitter = samples.emitter())

        assertEquals(MAX_DEFERRED_HOST_PERFORMANCE_EMITTERS, samples.size)
        assertEquals(1L, samples.first().durationMs)
        assertEquals(MAX_DEFERRED_HOST_PERFORMANCE_EMITTERS.toLong(), samples.last().durationMs)
    }

    /** Verifies a normally returned apply without a commit is classified as cancelled. */
    @Test
    fun commitMeasuredWorkCancelsANormalReturnWithoutACommit() =
        runTest {
            val samples = mutableListOf<Sample>()
            val attempt = recorder(samples = samples).begin(HostPerformanceOperationFfi.TIMELINE_APPLY)

            measureHostPerformanceCommit(attempt) { "stale" }

            assertEquals(HostPerformanceOutcomeFfi.CANCELLED, samples.single().outcome)
        }

    /** Verifies the explicit commit callback is the only successful apply boundary. */
    @Test
    fun commitMeasuredWorkSucceedsOnlyFromTheCommitCallback() =
        runTest {
            val samples = mutableListOf<Sample>()
            val attempt = recorder(samples = samples).begin(HostPerformanceOperationFfi.TIMELINE_APPLY)

            measureHostPerformanceCommit(attempt) { committed -> committed() }

            assertEquals(HostPerformanceOutcomeFfi.SUCCESS, samples.single().outcome)
        }

    /** Verifies a failed apply emits failure before preserving the exception. */
    @Test
    fun commitMeasuredWorkRecordsAndRethrowsFailures() =
        runTest {
            val samples = mutableListOf<Sample>()
            val attempt = recorder(samples = samples).begin(HostPerformanceOperationFfi.TIMELINE_APPLY)

            val thrown =
                runCatching {
                    measureHostPerformanceCommit(attempt) { throw IllegalStateException("apply failed") }
                }.exceptionOrNull()

            assertTrue(thrown is IllegalStateException)
            assertEquals(HostPerformanceOutcomeFfi.FAILURE, samples.single().outcome)
        }

    /** Verifies a cancelled apply emits cancellation before preserving cooperative cancellation. */
    @Test
    fun commitMeasuredWorkRecordsAndRethrowsCancellation() =
        runTest {
            val samples = mutableListOf<Sample>()
            val attempt = recorder(samples = samples).begin(HostPerformanceOperationFfi.TIMELINE_APPLY)

            val thrown =
                runCatching {
                    measureHostPerformanceCommit(attempt) { throw CancellationException("apply cancelled") }
                }.exceptionOrNull()

            assertTrue(thrown is CancellationException)
            assertEquals(HostPerformanceOutcomeFfi.CANCELLED, samples.single().outcome)
        }

    /** Verifies measured suspending work treats cancellation as terminal and rethrows it. */
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

    /** Verifies Android's catalog covers every shared non-Linux operation exactly once. */
    @Test
    fun androidCatalogContainsEverySharedStageAndNoLinuxStage() {
        val expected =
            HostPerformanceOperationFfi.entries.filterNot { it.name.startsWith("LINUX_") }.toSet()

        assertEquals(expected, ANDROID_HOST_PERFORMANCE_OPERATIONS)
        assertEquals(34, expected.size)
    }

    /** Verifies conversation-visible and composer-ready milestones reach MDK once at their real boundaries. */
    @Test
    fun conversationMilestonesSucceedExactlyOnceAtRenderedBoundaries() {
        var now = 100L
        val samples = mutableListOf<Sample>()
        val recorder = recorder(now = { now }, samples = samples)
        val timing =
            ConversationWindowPresentationTiming(
                nowMs = { now },
                beginHostAttempt = recorder::begin,
                emit = { _, _ -> },
            )

        timing.begin(receivedAtElapsedMs = 100L, ticket = null)
        timing.timelinePublished()
        now = 125L
        timing.windowVisible()
        timing.windowVisible()
        now = 140L
        timing.composerReady()
        timing.composerReady()
        timing.cancel()

        assertEquals(
            listOf(
                Sample(
                    HostPerformanceOperationFfi.CONVERSATION_LOCAL_VISIBLE,
                    25L,
                    HostPerformanceOutcomeFfi.SUCCESS,
                ),
                Sample(
                    HostPerformanceOperationFfi.CONVERSATION_COMPOSER_READY,
                    40L,
                    HostPerformanceOutcomeFfi.SUCCESS,
                ),
            ),
            samples,
        )
    }

    /** Verifies stale conversation callbacks cancel their original attempts without touching a replacement runtime. */
    @Test
    fun conversationReplacementCancelsOldMilestonesWithoutCompletingTheNewRuntime() {
        var generation = 1
        var now = 50L
        val originalSamples = mutableListOf<Sample>()
        val replacementSamples = mutableListOf<Sample>()
        val recorder = HostPerformanceRecorder(generation = { generation }, nowMs = { now })
        val originalOwner = Any()
        recorder.publishEmitter(originalOwner, ownerGeneration = generation, emitter = originalSamples.emitter())
        val staleTiming =
            ConversationWindowPresentationTiming(
                nowMs = { now },
                beginHostAttempt = recorder::begin,
                emit = { _, _ -> },
            )
        staleTiming.begin(receivedAtElapsedMs = now, ticket = null)

        generation = 2
        recorder.clearEmitter(originalOwner)
        recorder.publishEmitter(Any(), ownerGeneration = generation, emitter = replacementSamples.emitter())
        now = 75L
        staleTiming.cancel()
        staleTiming.timelinePublished()
        staleTiming.windowVisible()
        staleTiming.composerReady()

        assertEquals(
            listOf(
                Sample(
                    HostPerformanceOperationFfi.CONVERSATION_LOCAL_VISIBLE,
                    25L,
                    HostPerformanceOutcomeFfi.CANCELLED,
                ),
                Sample(
                    HostPerformanceOperationFfi.CONVERSATION_COMPOSER_READY,
                    25L,
                    HostPerformanceOutcomeFfi.CANCELLED,
                ),
            ),
            originalSamples,
        )
        assertTrue(replacementSamples.isEmpty())
    }

    /** Verifies a warm resume can complete from local readiness without waiting for Compose. */
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

    /** Verifies cold foreground timing remains open until local state is ready. */
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

    /** Verifies outbound attempts settle only after their claimed optimistic rows render. */
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

    /** Verifies a newer inbound frame owner cancels the superseded visibility attempt. */
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

    /** Verifies settings timing reflects the durable commit result rather than enqueue success. */
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
