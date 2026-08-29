package dev.ipf.whitenoise.android.diagnostics

import dev.ipf.whitenoise.android.state.StartupStageTraceSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StartupPerformanceDiagnosticsTest {
    @Test
    fun everyStartupStageUsedByAppStateHasAReservedPerfettoSection() {
        val appState = source("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt")
        val stagedPhases =
            Regex("""startupPerformance\.stage\(PerformancePhase\.([A-Z_]+)""")
                .findAll(appState)
                .map { match -> PerformancePhase.valueOf(match.groupValues[1]) }
                .toSet()

        assertTrue("AppState must retain attributed startup stages", stagedPhases.isNotEmpty())
        stagedPhases.forEach { phase ->
            val stageName = startupTraceStageFor(phase)
            assertNotNull("Missing trace stage for ${phase.wireName}", stageName)
            assertNotNull(
                "Missing trace section for ${phase.wireName}",
                StartupStageTraceSection.sectionFor(requireNotNull(stageName)),
            )
        }
    }

    @Test
    fun macrobenchmarkSectionsStayAlignedWithTypedStartupPhases() {
        val benchmark = source("benchmark/src/main/java/dev/ipf/whitenoise/android/benchmark/BenchmarkMetrics.kt")
        val measuredPhases =
            setOf(
                PerformancePhase.CLIENT_CONSTRUCTION,
                PerformancePhase.PRIVACY_RUNTIME_CONFIGURATION,
                PerformancePhase.MARMOT_START,
                PerformancePhase.NOTIFICATION_PLATFORM_SETUP,
                PerformancePhase.NOTIFICATION_PRIVACY_SETUP,
                PerformancePhase.ACCOUNT_REFRESH,
                PerformancePhase.ACCOUNT_ACTIVATION,
                PerformancePhase.DRAFT_RECONCILIATION,
                PerformancePhase.EXTERNAL_SIGNER_REGISTRATION,
            )

        measuredPhases.forEach { phase ->
            val stageName = requireNotNull(startupTraceStageFor(phase))
            val section = requireNotNull(StartupStageTraceSection.sectionFor(stageName))
            assertTrue("Macrobenchmark does not measure $section", benchmark.contains("\"$section\""))
        }
        assertEquals(measuredPhases.size, benchmark.countOccurrences("WhiteNoise.startup."))
    }

    private fun source(relativePath: String): String =
        sequenceOf(File(relativePath), File("../$relativePath"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing $relativePath")
}

private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
