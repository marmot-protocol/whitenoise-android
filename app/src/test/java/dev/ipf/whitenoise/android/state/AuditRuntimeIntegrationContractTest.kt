package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuditRuntimeIntegrationContractTest {
    @Test
    fun readinessMarkerFollowsSuccessfulRuntimeStart() {
        val source = appStateSource()
        val startBody =
            source
                .substringAfter("private suspend fun startMarmotWithNotificationListener")
                .substringBefore("private suspend fun resumeCompletedBootstrap")
        val started =
            startBody.indexOf(
                "runtimeStartResult.await().getOrThrowAtStartupStage(BootstrapStage.RUNTIME_START)",
            )
        val marker = startBody.indexOf("runtime.marmot.emitAuditRuntimeReadinessAfterStart()")

        assertTrue(started >= 0)
        assertTrue(marker > started)
    }

    private fun appStateSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing AppState.kt")
}
