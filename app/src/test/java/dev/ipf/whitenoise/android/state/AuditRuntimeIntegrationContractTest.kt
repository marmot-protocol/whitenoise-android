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
        val started = startBody.indexOf("runtimeStartResult.await().getOrThrow()")
        val marker = startBody.indexOf("runtime.marmot.emitAuditRuntimeReadinessAfterStart()")

        assertTrue(started >= 0)
        assertTrue(marker > started)
    }

    @Test
    fun auditedRuntimeEnablesRecorderOnlyWithCompleteConfiguration() {
        val configureBody =
            auditReadinessSource()
                .substringAfter("internal suspend fun MarmotInterface.configureAuditRuntime()")
        val trackerConfig = configureBody.indexOf("setAuditLogTrackerConfig(")
        val requiredGate = configureBody.indexOf("BuildConfig.WHITENOISE_AUDIT_RUNTIME_REQUIRED")
        val endpointGate = configureBody.indexOf("auditEndpoint != null")
        val authGate = configureBody.indexOf("auditAuthorizationBearerToken != null")
        val dataModeGate = configureBody.indexOf("BuildConfig.WHITENOISE_AUDIT_DATA_MODE == AUDIT_RUNTIME_DATA_MODE")
        val enableRecorder = configureBody.indexOf("setAuditLogSettings(auditLogSettings().copy(enabled = true))")

        assertTrue(trackerConfig >= 0)
        assertTrue(endpointGate > trackerConfig)
        assertTrue(authGate > endpointGate)
        assertTrue(requiredGate > authGate)
        assertTrue(dataModeGate > requiredGate)
        assertTrue(enableRecorder > dataModeGate)
    }

    private fun appStateSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing AppState.kt")

    private fun auditReadinessSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AuditRuntimeReadiness.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AuditRuntimeReadiness.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing AuditRuntimeReadiness.kt")
}
