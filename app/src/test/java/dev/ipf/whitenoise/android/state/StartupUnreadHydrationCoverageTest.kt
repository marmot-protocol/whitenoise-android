package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression coverage for cold-start contention with MDK's deferred group hydration. */
class StartupUnreadHydrationCoverageTest {
    @Test
    fun bootstrapLoadsRawUnreadCountsWithoutWaitingForMemberRosters() {
        val source = appStateSource().readText()
        val bootstrap = source.functionBody("bootstrapLocked")
        val startupRefresh = source.functionBody("refreshAccountsForBootstrap")

        assertTrue(
            "bootstrap must use the startup-scoped account refresh",
            "refreshAccountsForBootstrap()" in bootstrap,
        )
        assertFalse("bootstrap must not run the suppression-aware bulk refresh", "refreshAccounts()" in bootstrap)
        assertTrue(
            "startup account refresh must skip membership-aware roster reads",
            "refreshAccounts(loadMemberRosters = false)" in startupRefresh,
        )
    }

    @Test
    fun bootstrapPublishesReadyAtTheExistingLocalActivationBoundary() {
        val bootstrap = appStateSource().readText().functionBody("bootstrapLocked")
        val activate = bootstrap.indexOf("setActiveAccount(targetAccountRef)")
        val ready = bootstrap.indexOf("phase = AppPhase.Ready", startIndex = activate)

        assertTrue("bootstrap must select an account before publishing Ready", activate >= 0)
        assertTrue("Ready must be published from the account's local activation callback", ready > activate)
        assertTrue(
            "a failed sign-in must retain the previous bootstrap fallback",
            bootstrap.indexOf("if (phase == AppPhase.Bootstrapping)", startIndex = ready) > ready,
        )
    }

    @Test
    fun suppressionAwareUnreadReconciliationStartsAfterTheFirstLocalFrame() {
        val source = appStateSource().readText()
        val recorder = source.functionBody("recordAccountSwitchLocalSnapshotRendered")
        val launcher = source.functionBody("launchPendingStartupUnreadReconciliation")

        assertTrue(
            "the first-frame hook must release the deferred unread pass",
            "launchPendingStartupUnreadReconciliation()" in recorder,
        )
        assertTrue(
            "the deferred pass must run on a process-lifetime scope",
            "notificationScope.launch" in launcher,
        )
        assertTrue(
            "the deferred pass must restore suppression-aware counts",
            "refreshAccountUnreadCounts()" in launcher,
        )
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists) ?: error("Missing AppState.kt source file")
}
