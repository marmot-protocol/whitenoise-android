package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformanceConnectivity
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceSendStage
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PendingSendDiagnosticsTest {
    @Test
    fun pendingCheckpointsCaptureStageAttemptAndCoarseConnectivity() =
        runTest {
            val events = mutableListOf<PendingSendDiagnosticEvent>()
            var connectivity = PerformanceConnectivity.ONLINE_WITH_RELAY
            val tracker =
                PendingSendDiagnosticTracker(
                    scope = this,
                    nowMs = { testScheduler.currentTime },
                    connectivity = { connectivity },
                    record = events::add,
                )
            tracker.track("local-optimistic-id", trace())
            tracker.update("local-optimistic-id", PerformanceSendStage.FFI_ADMISSION, attempt = 2)

            advanceTimeBy(PendingSendDiagnosticTracker.FIRST_CHECKPOINT_MS)
            runCurrent()
            connectivity = PerformanceConnectivity.ONLINE_NO_RELAY
            tracker.update("local-optimistic-id", PerformanceSendStage.WAITING_PROJECTION)
            advanceTimeBy(
                PendingSendDiagnosticTracker.SECOND_CHECKPOINT_MS -
                    PendingSendDiagnosticTracker.FIRST_CHECKPOINT_MS,
            )
            runCurrent()
            tracker.clear()

            assertEquals(
                listOf(PerformancePhase.PENDING_CHECKPOINT_10S, PerformancePhase.PENDING_CHECKPOINT_60S),
                events.map(PendingSendDiagnosticEvent::phase),
            )
            assertEquals(PerformanceSendStage.FFI_ADMISSION, events[0].sendStage)
            assertEquals(2, events[0].attempt)
            assertEquals(PerformanceConnectivity.ONLINE_WITH_RELAY, events[0].connectivity)
            assertEquals(PerformanceSendStage.WAITING_PROJECTION, events[1].sendStage)
            assertEquals(PerformanceConnectivity.ONLINE_NO_RELAY, events[1].connectivity)
        }

    @Test
    fun projectionSurfacesSettleOneProcessOwnedTraceAcrossControllerReplacement() =
        runTest {
            val events = mutableListOf<PendingSendDiagnosticEvent>()
            val tracker =
                PendingSendDiagnosticTracker(
                    scope = this,
                    nowMs = { testScheduler.currentTime },
                    connectivity = { PerformanceConnectivity.OFFLINE },
                    record = events::add,
                )
            tracker.track("local-optimistic-id", trace())
            tracker.milestone(
                "local-optimistic-id",
                PerformancePhase.DURABLE_ACCEPTED,
                PerformanceSendStage.ACCEPTED_PENDING,
            )

            tracker.surfaceSettled("local-optimistic-id", PerformancePhase.CHAT_LIST_SETTLED)
            tracker.surfaceSettled("local-optimistic-id", PerformancePhase.TIMELINE_SETTLED)
            advanceTimeBy(PendingSendDiagnosticTracker.SECOND_CHECKPOINT_MS)
            runCurrent()

            assertEquals(
                listOf(
                    PerformancePhase.DURABLE_ACCEPTED,
                    PerformancePhase.CHAT_LIST_SETTLED,
                    PerformancePhase.TIMELINE_SETTLED,
                ),
                events.map(PendingSendDiagnosticEvent::phase),
            )
            assertTrue(events.none { it.phase == PerformancePhase.PENDING_CHECKPOINT_10S })
        }

    @Test
    fun trackerSourceCannotSerializeMessageOrRelayIdentity() {
        val source =
            sequenceOf(
                File("src/main/java/dev/ipf/whitenoise/android/state/PendingSendDiagnostics.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/state/PendingSendDiagnostics.kt"),
            ).first(File::isFile).readText()

        listOf(
            "plaintext",
            "messageIdHex",
            "groupIdHex",
            "accountRef",
            "relayUrl",
            "SharedPreferences",
            "DataStore",
            "FileOutputStream",
        ).forEach { denied -> assertTrue("Unexpected diagnostic input: $denied", denied !in source) }
    }

    @Test
    fun textSendAndBothProjectionSurfacesUseTheProcessOwnedTracker() {
        val controllersSource =
            sequenceOf(
                File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            ).first(File::isFile).readText()
        val trackerSource =
            sequenceOf(
                File("src/main/java/dev/ipf/whitenoise/android/state/PendingSendDiagnostics.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/state/PendingSendDiagnostics.kt"),
            ).first(File::isFile).readText()

        assertTrue(controllersSource.contains("pendingSendDiagnostics.track(tempId, trace)"))
        assertTrue(controllersSource.contains("PerformancePhase.PENDING_CHECKPOINT_10S").not())
        assertTrue(trackerSource.contains("PerformancePhase.DURABLE_ACCEPTED"))
        assertTrue(trackerSource.contains("PerformancePhase.ENGINE_PHASE_UNAVAILABLE"))
        assertTrue(controllersSource.contains("PerformancePhase.CHAT_LIST_SETTLED"))
        assertTrue(controllersSource.contains("PerformancePhase.TIMELINE_SETTLED"))
    }

    private fun trace(): PerformanceTrace =
        PerformanceTrace(
            operation = PerformanceOperation.TEXT_SEND,
            sessionGeneration = 1L,
            operationId = 1L,
            startedAtMs = 0L,
        )
}
