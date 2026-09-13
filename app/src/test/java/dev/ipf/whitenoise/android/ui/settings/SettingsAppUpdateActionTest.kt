package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings retry must not convert stale availability plus a failed check into an update request. */
class SettingsAppUpdateActionTest {
    /** Failed metadata remains a retry even when the previous successful check found a newer version. */
    @Test
    fun cachedNewerReleaseWithFailedRetryNeverStartsTheUpdater() =
        runBlocking {
            var checks = 0
            var updates = 0
            val failed = available().copy(lastAttemptErrorReport = "metadata unavailable")
            runSettingsAppUpdateAction(failed, refresh = {
                checks++
                failed
            }, onUpdate = { updates++ })
            assertEquals(1, checks)
            assertEquals(0, updates)
        }

    /** Only a successful explicit retry reaches the existing confirmation/trust flow. */
    @Test
    fun cachedNewerReleaseWithSuccessfulRetryDelegatesOnce() =
        runBlocking {
            var checks = 0
            var updates = 0
            runSettingsAppUpdateAction(
                available().copy(lastAttemptErrorReport = "metadata unavailable"),
                refresh = {
                    checks++
                    available()
                },
                onUpdate = { updates++ },
            )
            assertEquals(1, checks)
            assertEquals(1, updates)
        }

    /** A current row tap performs a fresh check rather than leaving the preserved entry inert. */
    @Test
    fun currentReleaseIsRecheckedBeforeDelegation() =
        runBlocking {
            var checks = 0
            var updates = 0
            runSettingsAppUpdateAction(
                available().copy(latestVersion = "2026.9.11"),
                refresh = {
                    checks++
                    available()
                },
                onUpdate = { updates++ },
            )
            assertEquals(1, checks)
            assertEquals(1, updates)
        }

    /** A known valid newer release retains the original tap-to-confirm path without an extra metadata check. */
    @Test
    fun knownAvailableReleaseDelegatesWithoutAnotherCheck() =
        runBlocking {
            var updates = 0
            runSettingsAppUpdateAction(
                available(),
                refresh = { error("Unexpected metadata check") },
                onUpdate = { updates++ },
            )
            assertEquals(1, updates)
        }

    private fun available() =
        AppUpdateInfo(
            installedVersion = "2026.9.11",
            latestVersion = "2026.9.13",
            checkedAtMillis = 1L,
            dismissedVersion = null,
            releasesBehind = 1,
        )
}
