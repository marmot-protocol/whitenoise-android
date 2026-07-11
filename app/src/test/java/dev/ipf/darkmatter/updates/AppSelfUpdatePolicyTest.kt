package dev.ipf.darkmatter.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelfUpdatePolicyTest {
    @Test
    fun zapstoreBuildStartsInAppFlow() {
        assertTrue(shouldStartInAppSelfUpdate(selfUpdateEnabled = true))
        assertFalse(shouldOpenExternalZapstoreListing(selfUpdateEnabled = true))
    }

    @Test
    fun playBuildOpensExternalListing() {
        assertFalse(shouldStartInAppSelfUpdate(selfUpdateEnabled = false))
        assertTrue(shouldOpenExternalZapstoreListing(selfUpdateEnabled = false))
    }

    @Test
    fun decideAppUpdateActionMatrix() {
        val newer =
            AppUpdateInfo(
                installedVersion = "2026.6.20",
                latestVersion = "2026.6.21",
                checkedAtMillis = 1L,
                dismissedVersion = null,
                releasesBehind = 1,
            )
        val current =
            newer.copy(
                latestVersion = "2026.6.20",
                releasesBehind = 0,
            )
        val older =
            newer.copy(
                latestVersion = "2026.6.19",
                releasesBehind = 0,
            )
        val noLatest =
            newer.copy(
                latestVersion = null,
                releasesBehind = null,
            )

        assertEquals(
            AppUpdateAction.StartInAppSelfUpdate("2026.6.21"),
            decideAppUpdateAction(selfUpdateEnabled = true, info = newer, appInForeground = true),
        )
        assertEquals(
            AppUpdateAction.OpenExternalListing,
            decideAppUpdateAction(selfUpdateEnabled = false, info = newer, appInForeground = true),
        )
        listOf(current, older, noLatest).forEach { unavailable ->
            assertEquals(
                AppUpdateAction.NoOp,
                decideAppUpdateAction(selfUpdateEnabled = true, info = unavailable, appInForeground = true),
            )
            assertEquals(
                AppUpdateAction.NoOp,
                decideAppUpdateAction(selfUpdateEnabled = false, info = unavailable, appInForeground = true),
            )
        }
        assertEquals(
            AppUpdateAction.NoOp,
            decideAppUpdateAction(selfUpdateEnabled = true, info = newer, appInForeground = false),
        )
        assertEquals(
            AppUpdateAction.NoOp,
            decideAppUpdateAction(selfUpdateEnabled = false, info = newer, appInForeground = false),
        )
    }
}
