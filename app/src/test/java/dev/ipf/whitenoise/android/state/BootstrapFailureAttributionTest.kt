package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootstrapFailureAttributionTest {
    @Test
    fun runtimeStartFailureKeepsItsCategoryAndAddsOnlyTheStableStage() {
        val nativeDetail = "database migration 75 is newer than supported migration 69"
        val runtimeFailure = MarmotKitException.Runtime(nativeDetail)
        val result = Result.failure<Unit>(runtimeFailure)
        val failure =
            assertThrows(BootstrapStageFailure::class.java) {
                result.getOrThrowAtStartupStage(BootstrapStage.RUNTIME_START)
            }

        val report =
            privacySafeErrorPresentation(
                operationCode = "APP_BOOTSTRAP",
                throwable = failure,
                appVersion = "test",
                androidVersion = "test",
                occurredAtUtc = "2026-09-16T00:00:00Z",
            ).report

        assertTrue(failure.message == "Startup failed at RUNTIME_START")
        assertTrue(report.contains("operation=APP_BOOTSTRAP"))
        assertTrue(report.contains("error=UNEXPECTED"))
        assertTrue(report.contains("detail=stage=RUNTIME_START"))
        assertFalse(report.contains(nativeDetail))
    }

    @Test
    fun runtimeStartCancellationRemainsCancellation() {
        val cancellation = CancellationException("test cancellation")

        val thrown =
            assertThrows(CancellationException::class.java) {
                Result.failure<Unit>(cancellation).getOrThrowAtStartupStage(BootstrapStage.RUNTIME_START)
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun productionRuntimeStartWaitUsesBoundedStageAttribution() {
        val source = appStateSource().readText().functionBody("startMarmotWithNotificationListener")

        assertTrue(
            "native start failures must identify the runtime-start boundary",
            "runtimeStartResult.await().getOrThrowAtStartupStage(BootstrapStage.RUNTIME_START)" in source,
        )
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists) ?: error("Missing AppState.kt source file")
}
