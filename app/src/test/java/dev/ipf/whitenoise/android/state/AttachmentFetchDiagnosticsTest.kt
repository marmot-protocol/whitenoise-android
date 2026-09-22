package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.diagnostics.PerformanceAttachmentState
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentFetchDiagnosticsTest {
    @Test
    fun explicitFetchRecordsClosedRequestAndNativeTransferState() {
        val events = mutableListOf<RecordedAttachmentEvent>()
        var now = 10L
        val diagnostics =
            AttachmentFetchDiagnostics.begin(
                priority = AttachmentDownloadPriority.Interactive,
                nowMs = { now },
                begin = { operation, trigger ->
                    assertEquals(PerformanceOperation.ATTACHMENT_FETCH, operation)
                    assertEquals(PerformanceTrigger.EXPLICIT, trigger)
                    trace()
                },
                record = { _, phase, elapsed, duration, result, layer, state ->
                    events += RecordedAttachmentEvent(phase, elapsed, duration, result, layer, state)
                },
            )

        now = 25L
        requireNotNull(diagnostics).transferUpdate(AttachmentTransferStateFfi.DOWNLOADING)
        now = 40L
        diagnostics.transferUpdate(AttachmentTransferStateFfi.READY)

        assertEquals(
            listOf(
                PerformancePhase.ATTACHMENT_REQUESTED,
                PerformancePhase.ATTACHMENT_TRANSFER_UPDATE,
                PerformancePhase.ATTACHMENT_TRANSFER_UPDATE,
            ),
            events.map(RecordedAttachmentEvent::phase),
        )
        assertEquals(PerformanceAttachmentState.DOWNLOADING, events[1].state)
        assertEquals(PerformanceResult.PENDING, events[1].result)
        assertEquals(PerformanceAttachmentState.READY, events[2].state)
        assertEquals(PerformanceResult.SUCCESS, events[2].result)
        assertEquals(PerformanceLayer.MDK, events[2].layer)
    }

    @Test
    fun everyNativeTransferStateMapsToTheClosedDiagnosticSchema() {
        val states = mutableListOf<PerformanceAttachmentState?>()
        val diagnostics =
            requireNotNull(
                AttachmentFetchDiagnostics.begin(
                    priority = AttachmentDownloadPriority.Automatic,
                    nowMs = { 10L },
                    begin = { _, _ -> trace() },
                    record = { _, phase, _, _, _, _, state ->
                        if (phase == PerformancePhase.ATTACHMENT_TRANSFER_UPDATE) states += state
                    },
                ),
            )

        AttachmentTransferStateFfi.entries.forEach(diagnostics::transferUpdate)

        assertEquals(AttachmentTransferStateFfi.entries.map { it.name }, states.map { it?.name })
    }

    @Test
    fun mediaOperationsAreWiredToProductionPaths() {
        val controller = source("Controllers.kt")
        val resolver = source("AttachmentPlaintextResolver.kt")
        val native = source("NativeAttachmentTransfers.kt")
        val diagnostics = source("AttachmentFetchDiagnostics.kt")

        assertTrue("startMediaSend(tempId)" in controller)
        assertTrue("beginMediaUpload(tempId)" in controller)
        assertTrue("beginMediaPublish(tempId)" in controller)
        assertTrue("AttachmentFetchDiagnostics.begin(priority)" in resolver)
        assertTrue("diagnostics?.transferUpdate" in native)
        assertTrue("PerformanceOperation.ATTACHMENT_FETCH" in diagnostics)
    }

    private data class RecordedAttachmentEvent(
        val phase: PerformancePhase,
        val elapsedMs: Long,
        val durationMs: Long,
        val result: PerformanceResult,
        val layer: PerformanceLayer,
        val state: PerformanceAttachmentState?,
    )

    private fun trace() =
        PerformanceTrace(
            operation = PerformanceOperation.ATTACHMENT_FETCH,
            trigger = PerformanceTrigger.EXPLICIT,
            sessionGeneration = 1L,
            operationId = 1L,
            startedAtMs = 10L,
        )

    private fun source(name: String): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/$name"),
        ).first(File::isFile).readText()
}
