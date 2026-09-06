package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppStartupReadinessTest {
    /** Pins every startup unread publication to the current account-list lifetime. */
    @Test
    fun staleBackgroundUnreadFoldGuardsAllStatePublication() {
        val source = appStateSource()
        val start = source.indexOf("private suspend fun refreshAccountUnreadCounts(")
        val end = source.indexOf("private suspend fun refreshAccountUnreadFold(", startIndex = start)
        check(start >= 0 && end > start) { "Missing account unread refresh section" }
        val refresh = source.substring(start, end)
        val revisionGuard = refresh.lastIndexOf("if (!refreshIsCurrent()) return")
        val atomicPublication = refresh.indexOf("accountUnreadStore.publishRefresh", startIndex = revisionGuard)

        assertTrue(revisionGuard >= 0)
        assertTrue(atomicPublication > revisionGuard)
    }

    @Test
    fun onlyExplicitRetryRestoresBootstrappingBeforeAwaitingTheProcessAttempt() {
        val source = appStateSource()
        val bootstrap = source.functionBody("bootstrap")
        val retry = source.functionBody("retryBootstrap")
        val restorePhase = retry.indexOf("phase = AppPhase.Bootstrapping")
        val awaitAttempt = retry.indexOf("bootstrap()")

        assertTrue(
            "background bootstrap must preserve an actionable failure",
            "phase = AppPhase.Bootstrapping" !in bootstrap,
        )
        assertTrue(restorePhase >= 0)
        assertTrue(awaitAttempt > restorePhase)
    }

    @Test
    fun bootstrapMountsLocalShellBeforeUnreadRosterFold() {
        val source = appStateSource()
        val bootstrap = source.functionBody("bootstrapLocked")

        val snapshot = bootstrap.indexOf("refreshAccountSnapshot")
        val signer = bootstrap.indexOf("reregisterExternalSigners()")
        val ready = bootstrap.indexOf("onActivated = { phase = AppPhase.Ready }")
        val blockingFold = bootstrap.indexOf("refreshAccountUnreadCounts")

        assertTrue(snapshot >= 0)
        assertTrue(signer > snapshot)
        assertTrue("Signer callbacks must be restored before the shell becomes operational", ready > signer)
        assertTrue("Unread roster folds must not return to the bootstrap critical path", blockingFold < 0)
    }

    @Test
    fun bootstrapTimesRequiredStagesSeparately() {
        val source = appStateSource()
        val runtime = source.functionBody("startBootstrapRuntime")
        val runtimeStart = source.functionBody("startMarmotWithNotificationListener")
        val expectedStages =
            listOf(
                "CLIENT_CONSTRUCTION",
                "PRIVACY_RUNTIME_CONFIGURATION",
                "MARMOT_START",
                "NOTIFICATION_PRIVACY_SETUP",
                "ACCOUNT_REFRESH",
                "DRAFT_RECONCILIATION",
                "ACCOUNT_ACTIVATION",
            )

        expectedStages.forEach { stage ->
            assertTrue(
                "Missing privacy-safe startup timing for $stage",
                "startupPerformance.stage(PerformancePhase.$stage" in source,
            )
        }
        assertTrue(
            "Privacy configuration must be distinguishable from engine start",
            runtime.indexOf("PerformancePhase.PRIVACY_RUNTIME_CONFIGURATION") in 0 until
                runtime.indexOf("startMarmotWithNotificationListener"),
        )
        assertTrue(
            "Engine start must retain its dedicated timing",
            "startupPerformance.stage(PerformancePhase.MARMOT_START)" in runtimeStart,
        )
    }

    @Test
    fun unreadFoldStartsOnlyAfterTheFirstLocalFrame() {
        val source = appStateSource()
        val recorder = source.functionBody("recordStartupLocalSnapshotRendered")

        assertTrue(recorder.indexOf("startupFirstLocalFrameRecorded = true") >= 0)
        assertTrue(
            recorder.indexOf("refreshAccountUnreadCounts") >
                recorder.indexOf("startupFirstLocalFrameRecorded = true"),
        )
        assertTrue("The deferred fold must survive screen disposal", recorder.contains("mutationsScope.launch"))
        assertTrue(recorder.contains("accountListLifetime.isCurrent"))
        assertTrue(recorder.contains("stillCurrent = accountListIsCurrent"))
    }

    private fun appStateSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing AppState.kt")
}

private fun String.functionBody(functionName: String): String {
    val signature = indexOf("fun $functionName(")
    check(signature >= 0) { "Missing function $functionName" }
    val openBrace = indexOf('{', signature)
    check(openBrace >= 0) { "Missing body for $functionName" }
    var depth = 0
    for (index in openBrace until length) {
        when (this[index]) {
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return substring(openBrace + 1, index)
            }
        }
    }
    error("Unterminated body for $functionName")
}
