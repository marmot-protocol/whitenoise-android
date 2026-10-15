package dev.ipf.whitenoise.android.diagnostics

import dev.ipf.marmotkit.AppPerformanceOperationSnapshotFfi
import dev.ipf.marmotkit.DurationHistogramSnapshotFfi
import dev.ipf.marmotkit.NativeStartupObservationFfi
import dev.ipf.marmotkit.NativeStartupOutcomeFfi
import dev.ipf.marmotkit.NativeStartupSnapshotFfi
import dev.ipf.marmotkit.NativeStartupStageFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDiagnosticSnapshotTest {
    @Test
    fun ordinaryBuildDoesNotEnableReadOrDisableNativeCapture() {
        val capture = NativeStartupDiagnosticCapture(enabled = false)
        var calls = 0
        val owner = Any()
        capture.bind(owner, { calls++ }, {
            calls++
            snapshot()
        }, { calls++ })
        assertNull(capture.snapshot())
        capture.unbind(owner)
        assertEquals(0, calls)
    }

    @Test
    fun repeatedRuntimePublicationDoesNotResetItsStartupCapture() {
        val capture = NativeStartupDiagnosticCapture(enabled = true)
        val owner = Any()
        var enables = 0
        var disables = 0
        repeat(2) { capture.bind(owner, { enables++ }, { snapshot() }, { disables++ }) }
        assertTrue(capture.snapshot()!!.contains("stage=ACCOUNT_RESOLUTION"))
        assertEquals(1, enables)
        capture.unbind(owner)
        assertEquals(1, disables)
        assertEquals("native_startup=unavailable\n", capture.snapshot())
    }

    @Test
    fun replacedOwnerCannotPublishOldObservationsOrDisableTheNewRuntime() {
        val capture = NativeStartupDiagnosticCapture(enabled = true)
        val old = Any()
        val current = Any()
        var currentDisables = 0
        capture.bind(
            old,
            {},
            {
                capture.bind(current, {}, { snapshot() }, { currentDisables++ })
                snapshot()
            },
            {},
        )
        assertEquals("native_startup=unavailable\n", capture.snapshot())
        capture.unbind(old)
        assertEquals(0, currentDisables)
        assertTrue(capture.snapshot()!!.contains("stage=ACCOUNT_RESOLUTION"))
    }

    @Test
    fun replacingRuntimeDisablesOldCaptureBeforeEnablingNewCapture() {
        val capture = NativeStartupDiagnosticCapture(enabled = true)
        val transitions = mutableListOf<String>()
        val old = Any()
        val current = Any()
        capture.bind(old, { transitions += "enable old" }, { snapshot() }, { transitions += "disable old" })
        capture.bind(current, { transitions += "enable current" }, { snapshot() }, { transitions += "disable current" })
        capture.unbind(old)
        assertEquals(listOf("enable old", "disable old", "enable current"), transitions)
        assertTrue(capture.snapshot()!!.contains("stage=ACCOUNT_RESOLUTION"))
        capture.unbind(current)
        assertEquals("disable current", transitions.last())
    }

    @Test
    fun failedCaptureNeverExportsTheExceptionOrPreventsHostOnlySharing() {
        val capture = NativeStartupDiagnosticCapture(enabled = true)
        capture.bind(Any(), {}, { error("nsec-private account=/private/database") }, {})
        val exported = withNativeStartupSnapshot("host_receipt=available", capture.snapshot())
        assertTrue(exported.contains("host_receipt=available"))
        assertTrue(exported.contains("native_startup=unavailable"))
        assertFalse(exported.contains("nsec"))
        assertFalse(exported.contains("/private"))
    }

    @Test
    fun recordAndByteBoundsKeepWholeDiagnosticLines() {
        val large = snapshot().copy(active = List(40) { observation() }, events = List(200) { observation() })
        val text = formatNativeStartupSnapshot(large)
        assertTrue(text.length <= 12 * 1024)
        assertTrue(text.lines().count { it.startsWith("active ") } <= 16)
        assertTrue(text.lines().count { it.startsWith("event ") } <= 96)
        assertTrue(text.endsWith("\n"))
        val host = "h".repeat(MAX_STARTUP_REPORT_BYTES)
        assertEquals(host, withNativeStartupSnapshot(host, text))
    }

    @Test
    fun maximalNumericObservationsReserveSpaceForTheWholeCapMarker() {
        val observation =
            observation().copy(
                elapsedMs = ULong.MAX_VALUE,
                stageElapsedMs = ULong.MAX_VALUE,
                migrationVersion = UInt.MAX_VALUE,
                migrationsCompleted = UInt.MAX_VALUE,
            )
        val text =
            formatNativeStartupSnapshot(
                snapshot().copy(active = List(16) { observation }, events = List(96) { observation }),
            )
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= 12 * 1024)
        assertTrue(text.endsWith("native_startup_records_capped=true\n"))
    }

    @Test
    fun nativeAggregateExportLabelsItsScopeAndFormatsOnlyReviewedFields() {
        val operation =
            AppPerformanceOperationSnapshotFfi(5uL, 3uL, 2uL, DurationHistogramSnapshotFfi(emptyList(), 1uL, 99uL))
        val text = formatNativePerformanceSnapshot(nativePerformanceFixture(operation))
        assertTrue(text.contains("scope=process_cumulative"))
        assertTrue(text.contains("account_open attempts=5 successes=3 failures=2 duration_sum_ms=99"))
        assertTrue(text.contains("message_publish attempts=5"))
        assertFalse(text.contains("runtimeOperations"))
        assertTrue(text.toByteArray().size <= 12 * 1024)
    }

    @Test
    fun byteCappedHistoryRetainsTheNewestObservation() {
        val events =
            List(96) { index ->
                observation().copy(
                    elapsedMs = index.toULong(),
                    stageElapsedMs = ULong.MAX_VALUE,
                    migrationVersion = UInt.MAX_VALUE,
                    migrationsCompleted = UInt.MAX_VALUE,
                )
            }
        val text = formatNativeStartupSnapshot(snapshot().copy(active = emptyList(), events = events))
        assertTrue(text.contains("history_order=newest_first"))
        assertTrue(text.contains("elapsed_ms=95 stage_elapsed_ms="))
        assertFalse(text.contains("elapsed_ms=0 stage_elapsed_ms="))
        assertTrue(text.endsWith("native_startup_records_capped=true\n"))
    }

    private fun snapshot() = NativeStartupSnapshotFfi(true, listOf(observation()), emptyList(), 0uL, 0uL)

    private fun observation() =
        NativeStartupObservationFfi(
            1uL,
            NativeStartupStageFfi.ACCOUNT_RESOLUTION,
            10uL,
            2uL,
            null,
            0u,
            NativeStartupOutcomeFfi.ACTIVE,
        )
}
