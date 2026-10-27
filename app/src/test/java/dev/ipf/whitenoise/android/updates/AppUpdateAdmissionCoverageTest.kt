package dev.ipf.whitenoise.android.updates

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards automatic entry-point wiring; repository and worker tests execute timing, policy and cancellation. */
class AppUpdateAdmissionCoverageTest {
    /** Startup and both lifecycle edges must use the shared automatic path, never a prechecked manual fetch. */
    @Test
    fun lifecycleAndStartupUseAutomaticAdmission() {
        val state = source("state/AppState.kt")
        val refresh = state.functionBody("refreshAppUpdate")
        assertTrue("if (force)" in refresh)
        assertTrue("appUpdateRepository.refreshAutomatically()" in refresh)
        assertFalse("shouldCheck(" in refresh)
        assertEquals(1, Regex("appUpdateRepository\\.refresh\\(").findAll(state).count())
        assertTrue(
            "refreshAppUpdate(force = false, notifyIfNewer = notifyIfNewer)" in state,
        )
        assertEquals(
            3,
            Regex("notificationScope\\.launch \\{ refreshAppUpdateIfStale\\(").findAll(state).count(),
        )
    }

    /** WorkManager cannot bypass distribution, network or retry policy by calling explicit refresh. */
    @Test
    fun workerUsesTheSameAutomaticRepositoryBoundary() {
        val worker = source("updates/AppUpdateWorker.kt")
        val work = worker.functionBody("doWork")
        assertTrue("repository.refreshAutomatically()" in work)
        assertFalse("repository.refresh()" in work)
        assertTrue("if (!BuildConfig.SELF_UPDATE_ENABLED) return null" in worker.functionBody("schedule"))
        assertTrue("NetworkType.CONNECTED" in worker.functionBody("schedule"))
    }

    /** Shared admission must surround the only release fetch and read timing inside flight acquisition. */
    @Test
    fun repositoryFetchRequiresSharedAdmission() {
        val repository = source("updates/AppUpdateRepository.kt")
        val admission = repository.functionBody("refreshAdmitted")
        assertTrue("!selfUpdateEnabled" in admission)
        assertTrue("automatic && !automaticNetworkAllowed()" in admission)
        assertTrue("AppUpdateAdmission.run(shouldStart = { !automatic || shouldCheck() })" in admission)
        assertTrue("fetchAndPersist(installedVersion)" in admission)
        assertEquals(1, Regex("fetchLatestRelease\\(").findAll(repository).count())
        assertEquals(1, Regex("fetchAndPersist\\(installedVersion\\)").findAll(repository).count())
    }

    /** The sole user-driven retry remains deliberate and does not silently inherit the automatic cooldown. */
    @Test
    fun settingsCheckNowIsExplicit() {
        val settings = source("ui/settings/SettingsScreen.kt")
        assertTrue("appState.refreshAppUpdate(force = true, notifyIfNewer = false)" in settings)
        assertTrue("refreshAdmitted(installedVersion, automatic = false)" in source("updates/AppUpdateRepository.kt"))
    }

    /** Resolves production source whether Gradle launches the tests from the root or app module. */
    private fun source(path: String): String =
        listOf(File("src/main/java/dev/ipf/whitenoise/android", path), File("app/src/main/java/dev/ipf/whitenoise/android", path))
            .first { it.isFile }
            .readText()
}
