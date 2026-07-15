package dev.ipf.whitenoise.android.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateNotificationDecisionTest {
    @Test
    fun postsOnlyForBackgroundedBannerWorthyUpdatesWhenRequested() {
        val update =
            AppUpdateInfo(
                installedVersion = "2026.6.10",
                latestVersion = "2026.6.20",
                checkedAtMillis = 1L,
                dismissedVersion = null,
                releasesBehind = null,
            )

        assertTrue(shouldPostAppUpdateNotification(update, notifyIfNewer = true, appInForeground = false))
        assertFalse(shouldPostAppUpdateNotification(update, notifyIfNewer = false, appInForeground = false))
        assertFalse(shouldPostAppUpdateNotification(update, notifyIfNewer = true, appInForeground = true))
        assertFalse(
            shouldPostAppUpdateNotification(
                update.copy(dismissedVersion = "2026.6.20"),
                notifyIfNewer = true,
                appInForeground = false,
            ),
        )
    }
}
