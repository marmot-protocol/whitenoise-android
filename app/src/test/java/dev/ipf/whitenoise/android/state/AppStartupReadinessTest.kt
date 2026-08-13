package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppStartupReadinessTest {
    @Test
    fun staleBackgroundUnreadFoldCannotOverwriteANewerAccountList() {
        assertTrue(startupUnreadRefreshIsCurrent(expectedRevision = 4L, currentRevision = 4L))
        assertFalse(startupUnreadRefreshIsCurrent(expectedRevision = 4L, currentRevision = 5L))
    }

    @Test
    fun staleBackgroundUnreadFoldGuardsAllStatePublication() {
        val source = appStateSource()
        val start = source.indexOf("private suspend fun refreshAccountUnreadCounts(")
        val end = source.indexOf("private suspend fun refreshAccountUnreadFold(", startIndex = start)
        check(start >= 0 && end > start) { "Missing account unread refresh section" }
        val refresh = source.substring(start, end)
        val revisionGuard = refresh.lastIndexOf("if (!stillCurrent()) return")
        val manualUnreadPublication = refresh.indexOf("updateAccountManualUnread", startIndex = revisionGuard)
        val countPublication = refresh.indexOf("accountUnreadCounts = merged", startIndex = revisionGuard)

        assertTrue(revisionGuard >= 0)
        assertTrue(manualUnreadPublication > revisionGuard)
        assertTrue(countPublication > manualUnreadPublication)
    }

    @Test
    fun failedRetryRestoresBootstrappingBeforeLaunchingAFullAttempt() {
        val bootstrap = appStateSource().functionBody("bootstrap")
        val newAttempt = bootstrap.indexOf("mutationsScope.async { bootstrapLocked() }")
        val restorePhase = bootstrap.lastIndexOf("phase = AppPhase.Bootstrapping", startIndex = newAttempt)

        assertTrue(newAttempt >= 0)
        assertTrue(restorePhase in 0 until newAttempt)
    }

    @Test
    fun bootstrapMountsLocalShellBeforeUnreadRosterFold() {
        val source = appStateSource()
        val bootstrap = source.functionBody("bootstrapLocked")

        val snapshot = bootstrap.indexOf("refreshAccountSnapshot()")
        val signer = bootstrap.indexOf("reregisterExternalSigners()")
        val ready = bootstrap.indexOf("onActivated = { phase = AppPhase.Ready }")
        val blockingFold = bootstrap.indexOf("refreshAccountUnreadCounts")

        assertTrue(snapshot >= 0)
        assertTrue(signer > snapshot)
        assertTrue(ready > signer)
        assertTrue("Unread roster folds must not return to the bootstrap critical path", blockingFold < 0)
    }

    @Test
    fun bootstrapTimesRequiredStagesSeparately() {
        val source = appStateSource()
        val runtime = source.functionBody("startBootstrapRuntime")
        val expectedStages =
            listOf(
                "client-construction",
                "privacy-runtime-configuration",
                "marmot-start",
                "notification-privacy-setup",
                "account-refresh",
                "draft-reconciliation",
                "account-activation",
            )

        expectedStages.forEach { stage ->
            assertTrue("Missing privacy-safe startup timing for $stage", "traceStartupStage(\"$stage\")" in source)
        }
        assertTrue(
            "Privacy configuration must be distinguishable from engine start",
            runtime.indexOf("privacy-runtime-configuration") < runtime.indexOf("marmot-start"),
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
        assertTrue(recorder.contains("StartupUnreadRevisionGuard"))
        assertTrue(recorder.contains("revisionGuard::isCurrent"))
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
