package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The forward trace records only closed phases, outcome shapes, durations and bounded counts. */
class ForwardDiagnosticsTest {
    private data class Recorded(
        val phase: PerformancePhase,
        val elapsedMs: Long,
        val durationMs: Long,
        val result: PerformanceResult,
        val layer: PerformanceLayer,
        val count: Int?,
    )

    private val events = mutableListOf<Recorded>()
    private var now = 1_000L

    /** Builds a trace whose recorder appends to [events] and whose clock is [now]. */
    private fun diagnostics(): ForwardDiagnostics =
        requireNotNull(
            ForwardDiagnostics.begin(
                nowMs = { now },
                begin = { operation ->
                    assertEquals(PerformanceOperation.MESSAGE_FORWARD, operation)
                    PerformanceTrace(operation, sessionGeneration = 1L, operationId = 1L, startedAtMs = 1_000L)
                },
                record = { _, phase, elapsed, duration, result, layer, count ->
                    events += Recorded(phase, elapsed, duration, result, layer, count)
                },
            ),
        )

    @Test
    fun inactiveSessionYieldsNoOwnerAndSpansRunUnchanged() =
        runTest {
            val absent =
                ForwardDiagnostics.begin(
                    nowMs = { now },
                    begin = { null },
                    record = { _, _, _, _, _, _, _ -> },
                )
            assertNull(absent)
            val result = absent.span(ForwardDiagnostics::uploadStart, ForwardDiagnostics::uploadReturn) { 7 }
            assertEquals(7, result)
        }

    /** A cached source skips the download pair, and every later phase carries its own duration. */
    @Test
    fun cachedForwardRecordsLookupHitUploadLockAndPublishDurations() =
        runTest {
            val diagnostics = diagnostics()
            val lookupStart = diagnostics.startSpan()
            now += 3
            diagnostics.sourceLookup(hit = true, startedAtMs = lookupStart)
            diagnostics.span({}, ForwardDiagnostics::sourceReady) { now += 2 }
            diagnostics.span(ForwardDiagnostics::uploadStart, ForwardDiagnostics::uploadReturn) { now += 40 }
            val lockRequested = diagnostics.startSpan()
            now += 15
            diagnostics.commitLockAcquired(lockRequested)
            diagnostics.span(ForwardDiagnostics::publishStart, ForwardDiagnostics::publishReturn) { now += 60 }

            assertEquals(
                listOf(
                    PerformancePhase.FORWARD_SOURCE_LOOKUP,
                    PerformancePhase.FORWARD_SOURCE_READY,
                    PerformancePhase.MEDIA_UPLOAD_START,
                    PerformancePhase.MEDIA_UPLOAD_RETURN,
                    PerformancePhase.COMMIT_LOCK_ACQUIRED,
                    PerformancePhase.MEDIA_PUBLISH_START,
                    PerformancePhase.MEDIA_PUBLISH_RETURN,
                ),
                events.map(Recorded::phase),
            )
            assertEquals(PerformanceResult.SUCCESS, events[0].result)
            assertEquals(PerformanceLayer.STORAGE, events[0].layer)
            assertEquals(3L, events[0].durationMs)
            assertEquals(2L, events[1].durationMs)
            assertEquals(PerformanceResult.PENDING, events[2].result)
            assertEquals(PerformanceLayer.FFI, events[3].layer)
            assertEquals(40L, events[3].durationMs)
            assertEquals(15L, events[4].durationMs)
            assertEquals(PerformanceLayer.MDK, events[6].layer)
            assertEquals(60L, events[6].durationMs)
            assertEquals(120L, events[6].elapsedMs)
            assertTrue(events.all { it.count == null })
        }

    /** A source served from MarmotKit's retained copy is a hit attributed to the engine, not to Android storage. */
    @Test
    fun retainedSourceHitIsAttributedToTheEngineLayer() {
        val diagnostics = diagnostics()
        val lookupStart = diagnostics.startSpan()
        now += 6
        diagnostics.sourceLookup(hit = true, startedAtMs = lookupStart, native = true)

        assertEquals(PerformancePhase.FORWARD_SOURCE_LOOKUP, events.single().phase)
        assertEquals(PerformanceResult.SUCCESS, events.single().result)
        assertEquals(PerformanceLayer.MDK, events.single().layer)
        assertEquals(6L, events.single().durationMs)
    }

    /** A cache miss opens the native download pair, and a failed download closes it as failure. */
    @Test
    fun uncachedForwardRecordsMissAndDownloadOutcomeShapeOnly() =
        runTest {
            val diagnostics = diagnostics()
            diagnostics.sourceLookup(hit = false, startedAtMs = diagnostics.startSpan())
            try {
                diagnostics.span(ForwardDiagnostics::sourceDownloadStart, ForwardDiagnostics::sourceDownloadReturn) {
                    now += 500
                    error("blossom unreachable")
                }
                fail("download failure must propagate")
            } catch (_: IllegalStateException) {
                // The forward's own classification sees the original exception.
            }

            assertEquals(
                listOf(
                    PerformancePhase.FORWARD_SOURCE_LOOKUP,
                    PerformancePhase.FORWARD_SOURCE_DOWNLOAD_START,
                    PerformancePhase.FORWARD_SOURCE_DOWNLOAD_RETURN,
                ),
                events.map(Recorded::phase),
            )
            assertEquals(PerformanceResult.PENDING, events[0].result)
            assertEquals(PerformanceLayer.MDK, events[1].layer)
            assertEquals(PerformanceResult.FAILURE, events[2].result)
            assertEquals(500L, events[2].durationMs)
        }

    /** Cancellation closes an open span as dropped and is rethrown unchanged. */
    @Test
    fun cancelledSpanClosesAsDroppedAndRethrows() =
        runTest {
            val diagnostics = diagnostics()
            try {
                diagnostics.span(ForwardDiagnostics::publishStart, ForwardDiagnostics::publishReturn) {
                    throw CancellationException("forward cancelled")
                }
                fail("cancellation must propagate")
            } catch (_: CancellationException) {
                // Expected.
            }
            assertEquals(PerformanceResult.DROPPED, events.last().result)
            assertEquals(PerformancePhase.MEDIA_PUBLISH_RETURN, events.last().phase)
        }

    /** The terminal snapshot maps completed, cancelled and failed to the closed result set with a bounded count. */
    @Test
    fun terminalSnapshotMapsToClosedResultsAndCompletedTargetCount() {
        val diagnostics = diagnostics()
        val target = ForwardTargetProgress(groupIdHex = "target", totalAttachments = 1, totalMessages = 1)
        val completed = target.copy(phase = ForwardTargetPhase.Completed)
        val failed = target.copy(phase = ForwardTargetPhase.Failed, failureStage = ForwardFailureStage.Upload)

        diagnostics.terminal(snapshot(ForwardOperationPhase.Completed, listOf(completed, completed)))
        diagnostics.terminal(snapshot(ForwardOperationPhase.Cancelled, listOf(target)))
        diagnostics.terminal(snapshot(ForwardOperationPhase.PartialFailure, listOf(completed, failed)))
        diagnostics.terminal(snapshot(ForwardOperationPhase.Failed, listOf(failed)))

        assertTrue(events.all { it.phase == PerformancePhase.FORWARD_COMPLETE })
        assertEquals(
            listOf(
                PerformanceResult.SUCCESS,
                PerformanceResult.DROPPED,
                PerformanceResult.FAILURE,
                PerformanceResult.FAILURE,
            ),
            events.map(Recorded::result),
        )
        assertEquals(listOf(2, 0, 1, 0), events.map(Recorded::count))
    }

    /** Builds one operation snapshot for the given targets. */
    private fun snapshot(
        phase: ForwardOperationPhase,
        targets: List<ForwardTargetProgress>,
    ) = ForwardOperationSnapshot(
        phase = phase,
        preparedAttachments = 1,
        totalAttachments = 1,
        targets = targets,
    )
}
