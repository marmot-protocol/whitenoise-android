package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression coverage for cold-start contention with MDK's deferred group hydration. */
class StartupUnreadHydrationCoverageTest {
    @Test
    fun bootstrapDefersUnreadRosterWorkUntilAfterTheLocalSnapshot() {
        val source = appStateSource().readText()
        val bootstrap = source.functionBody("bootstrapLocked")

        assertTrue(
            "bootstrap must load only the account snapshot on its critical path",
            "refreshAccountSnapshot" in bootstrap,
        )
        assertTrue(
            "bootstrap must retain the snapshot for the post-frame unread pass",
            "prepareStartupUnreadRefresh(refreshedAccounts)" in bootstrap,
        )
        assertFalse(
            "bootstrap must not run the suppression-aware bulk refresh",
            "refreshAccountUnreadCounts" in bootstrap,
        )
    }

    @Test
    fun bootstrapPublishesReadyOnlyAtTheLocalActivationBoundary() {
        val bootstrap = appStateSource().readText().functionBody("bootstrapLocked")
        val activate = bootstrap.indexOf("setActiveAccount(")
        val ready = bootstrap.indexOf("onActivated = { phase = AppPhase.Ready }", startIndex = activate)
        val requireActivation = bootstrap.indexOf("check(activated)", startIndex = ready)

        assertTrue("bootstrap must select an account before publishing Ready", activate >= 0)
        assertTrue("Ready must be published from the account's local activation callback", ready > activate)
        assertTrue(
            "bootstrap must fail closed when account activation never reaches that callback",
            requireActivation > ready,
        )
        assertFalse(
            "failed activation must never fall through to Ready",
            "if (phase == AppPhase.Bootstrapping)" in bootstrap,
        )
    }

    /** Keeps deferred unread hydration suppression-aware and account-list scoped. */
    @Test
    fun suppressionAwareUnreadReconciliationStartsAfterTheFirstLocalFrame() {
        val source = appStateSource().readText()
        val recorder = source.functionBody("recordStartupLocalSnapshotRendered")

        assertTrue(
            "the first-frame hook must release the deferred unread pass",
            "pendingStartupUnreadRefresh" in recorder,
        )
        assertTrue(
            "the deferred pass must run on a process-lifetime scope",
            "mutationsScope.launch" in recorder,
        )
        assertTrue(
            "the deferred pass must restore suppression-aware counts",
            "refreshAccountUnreadCounts" in recorder,
        )
        assertTrue(
            "the deferred pass must reject stale account snapshots",
            "accountListLifetime.isCurrent" in recorder && "stillCurrent = accountListIsCurrent" in recorder,
        )
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists) ?: error("Missing AppState.kt source file")
}
