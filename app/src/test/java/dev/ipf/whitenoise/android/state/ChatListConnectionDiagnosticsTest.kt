package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformanceConnectivity
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnosticEmitter
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Readiness attribution reuses the opt-in, bounded emitter without exposing owner keys. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListConnectionDiagnosticsTest {
    /** Delivery settings are not read at all when the local collector has not been enabled. */
    @Test
    fun inactiveCollectionDoesNotReadConfigurationOrRetainEvents() {
        var configurationReads = 0
        val diagnostics =
            ChatListConnectionDiagnostics(
                configuration = {
                    configurationReads++
                    testConfiguration()
                },
                beginTrace = { null },
                nowMs = { 0L },
            )
        diagnostics.begin(PerformancePhase.CONNECTION_SESSION_ATTEMPT, testState())
        diagnostics.event(PerformancePhase.CONNECTION_READY, testState())
        assertEquals(0, configurationReads)
    }

    /** Runtime counters and source phases are enough to explain a five-second readiness wait. */
    @Test
    fun episodeContainsElapsedPhasesAndConfigurationButNoOwnerIdentifiers() {
        var now = 0L
        val output = mutableListOf<String>()
        val emitter =
            PerformanceDiagnosticEmitter(
                available = true,
                appRevision = "candidate",
                mdkRevision = "7692e266",
                nowMs = { now },
                sink = output::add,
            )
        emitter.start()
        val diagnostics = diagnostics(emitter) { now }
        val state = testState()
        diagnostics.begin(PerformancePhase.CONNECTION_FOREGROUND, state)
        now = 5_000L
        diagnostics.event(PerformancePhase.CONNECTION_CATCH_UP_SUCCEEDED, state)

        assertTrue(output.any { "phase=connection_mode_local" in it })
        assertTrue(output.any { "phase=connection_keep_connected_on" in it })
        assertTrue(output.any { "phase=connection_foreground" in it })
        val completion = output.single { "phase=connection_catch_up_succeeded" in it }
        assertTrue(completion.contains("elapsed_ms=5000"))
        assertTrue(completion.contains("count=7"))
        assertFalse(output.any { "private-account" in it || "account=" in it || "relay=" in it })
        diagnostics.clear()
        val count = output.size
        diagnostics.event(PerformancePhase.CONNECTION_SESSION_ENDED, state)
        assertEquals(count, output.size)
    }

    /** Ending explicit opt-in rejects events even when a controller still holds an episode. */
    @Test
    fun stoppedCollectionRejectsPendingControllerEvents() {
        val output = mutableListOf<String>()
        val emitter =
            PerformanceDiagnosticEmitter(
                available = true,
                appRevision = "candidate",
                mdkRevision = "7692e266",
                nowMs = { 0L },
                sink = output::add,
            )
        emitter.start()
        val diagnostics = diagnostics(emitter) { 0L }
        diagnostics.begin(PerformancePhase.CONNECTION_SESSION_VALIDATION, testState())
        emitter.stop()
        val count = output.size
        diagnostics.event(PerformancePhase.CONNECTION_READY, testState())
        assertEquals(count, output.size)
    }

    /** Each effective delivery configuration and retry deadline has closed, observable fields. */
    @Test
    fun deliveryModesReadinessAndRetryWaitAreAttributedWithoutErrorText() {
        for (fcm in listOf(false, true)) {
            for (keepConnected in listOf(false, true)) {
                val output = mutableListOf<String>()
                val emitter =
                    PerformanceDiagnosticEmitter(
                        available = true,
                        appRevision = "candidate",
                        mdkRevision = "7692e266",
                        nowMs = { 0L },
                        sink = output::add,
                    )
                emitter.start()
                val diagnostics =
                    diagnostics(
                        emitter,
                        configuration = { testConfiguration().copy(fcm = fcm, keepConnected = keepConnected) },
                        nowMs = { 0L },
                    )
                diagnostics.begin(
                    PerformancePhase.CONNECTION_SESSION_ATTEMPT,
                    testState().copy(phase = ChatListConnectionPhase.Attempting),
                )
                diagnostics.event(PerformancePhase.CONNECTION_SUBSCRIPTION_FAILED, testState())
                diagnostics.event(PerformancePhase.CONNECTION_RETRY_WAIT, testState(), durationMs = 5_000L)
                assertTrue(output.any { "phase=connection_mode_${if (fcm) "fcm" else "local"}" in it })
                assertTrue(output.any { "phase=connection_keep_connected_${if (keepConnected) "on" else "off"}" in it })
                assertTrue(output.any { "phase=connection_phase_attempting" in it })
                assertTrue(output.any { "phase=connection_phase_idle" in it })
                assertTrue(output.single { "phase=connection_retry_wait" in it }.contains("duration_ms=5000"))
                assertFalse(output.any { "private-account" in it })
            }
        }
    }

    /** A retired subscription cannot attribute completion or failure to a replacement account. */
    @Test
    fun staleSubscriptionBoundariesCannotEnterTheReplacementEpisode() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val output = mutableListOf<String>()
            val emitter =
                PerformanceDiagnosticEmitter(
                    available = true,
                    appRevision = "candidate",
                    mdkRevision = "7692e266",
                    nowMs = { 0L },
                    sink = output::add,
                )
            emitter.start()
            val owner =
                ChatListConnectionOwner(
                    runtimeGeneration = { 7 },
                    hasValidatedInternet = { true },
                    launchCatchUpRequest = { error("no transport call is needed for attribution") },
                    hasCurrentSubscriptions = { true },
                    diagnostics = diagnostics(emitter) { 0L },
                )
            try {
                val retired = owner.beginSessionAttempt("retired-private-account", 1L)
                val current = owner.beginSubscriptionValidation("replacement-private-account", 2L)
                val count = output.size
                owner.noteSubscriptionBoundary(retired, PerformancePhase.CONNECTION_SUBSCRIPTION_FAILED)
                owner.noteSubscriptionBoundary(retired, PerformancePhase.CONNECTION_CHATS_COMPLETED)
                assertEquals(count, output.size)
                owner.noteSubscriptionBoundary(current, PerformancePhase.CONNECTION_CHATS_OPEN)
                assertTrue(output.any { "phase=connection_chats_open" in it })
                assertEquals(ChatListConnectionPhase.Validating, owner.state.phase)
                owner.invalidate()
                assertTrue(output.any { "phase=connection_invalidated" in it })
                assertFalse(
                    "healthy account teardown is not a network-loss event",
                    output.any { "phase=connection_network_lost" in it },
                )
                assertFalse(output.any { "private-account" in it })
            } finally {
                owner.clear()
                Dispatchers.resetMain()
            }
        }

    /** Unchanged fast polls cannot consume the session budget needed for later recovery boundaries. */
    @Test
    fun repeatedRelaySamplesAreSuppressedButConnectivityAndReadinessEdgesRemain() {
        val output = mutableListOf<String>()
        var connectivity = PerformanceConnectivity.ONLINE_NO_RELAY
        val emitter =
            PerformanceDiagnosticEmitter(
                available = true,
                appRevision = "candidate",
                mdkRevision = "7692e266",
                nowMs = { 0L },
                sink = output::add,
            )
        emitter.start()
        val diagnostics =
            diagnostics(
                emitter,
                configuration = { testConfiguration().copy(connectivity = connectivity) },
                nowMs = { 0L },
            )
        val validating = testState().copy(phase = ChatListConnectionPhase.Validating)
        diagnostics.begin(PerformancePhase.CONNECTION_RELAY_SAMPLE, validating)
        val initialCount = output.size
        repeat(1_000) { diagnostics.event(PerformancePhase.CONNECTION_RELAY_SAMPLE, validating) }
        assertEquals(initialCount, output.size)
        connectivity = PerformanceConnectivity.ONLINE_WITH_RELAY
        diagnostics.event(PerformancePhase.CONNECTION_RELAY_SAMPLE, validating)
        assertEquals(initialCount + 2, output.size)
        val ready = validating.copy(phase = ChatListConnectionPhase.Ready)
        diagnostics.event(PerformancePhase.CONNECTION_RELAY_SAMPLE, ready)
        assertEquals(initialCount + 4, output.size)
        diagnostics.event(PerformancePhase.CONNECTION_CATCH_UP_SUCCEEDED, ready)
        assertTrue(output.any { "phase=connection_catch_up_succeeded" in it })
    }

    /** Uses the production serializer rather than a second test-only diagnostic format. */
    private fun diagnostics(
        emitter: PerformanceDiagnosticEmitter,
        configuration: () -> ChatListConnectionDiagnosticConfiguration = ::testConfiguration,
        nowMs: () -> Long,
    ): ChatListConnectionDiagnostics =
        ChatListConnectionDiagnostics(
            configuration = configuration,
            beginTrace = {
                emitter.begin(PerformanceOperation.CHAT_LIST_REFRESH, PerformanceTrigger.CHAT_LIST_READINESS)
            },
            record = { trace, phase, elapsed, duration, generation, attempt, connectivity ->
                emitter.record(
                    trace = trace,
                    phase = phase,
                    elapsedMs = elapsed,
                    durationMs = duration,
                    count = generation,
                    attempt = attempt,
                    connectivity = connectivity,
                )
            },
            nowMs = nowMs,
        )

    /** Anonymous numeric runtime data; the account key deliberately must not reach serialized output. */
    private fun testState(): ChatListConnectionState =
        ChatListConnectionState(
            accountRef = "private-account",
            runtimeGeneration = 7,
            bindEpoch = 9,
            sessionAttemptId = 2,
        )

    /** Existing local/persistent delivery is expressed only as flags and a coarse connectivity enum. */
    private fun testConfiguration(): ChatListConnectionDiagnosticConfiguration =
        ChatListConnectionDiagnosticConfiguration(
            fcm = false,
            keepConnected = true,
            connectivity = PerformanceConnectivity.ONLINE_WITH_RELAY,
        )
}
