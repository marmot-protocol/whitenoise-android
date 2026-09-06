package dev.ipf.whitenoise.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelfUpdatePolicyTest {
    @Test
    fun zapstoreLookupUsesTheProductionApplicationIdentity() {
        assertEquals("dev.ipf.whitenoise.android", AppUpdateConstants.WHITENOISE_ZAPSTORE_APP_ID)
    }

    @Test
    fun zapstoreBuildStartsInAppFlowForStrictlyNewerVersion() {
        assertTrue(
            shouldStartInAppSelfUpdate(
                selfUpdateEnabled = true,
                installedVersion = "2026.9.2",
                targetVersion = "2026.9.4",
            ),
        )
    }

    @Test
    fun playBuildDoesNotSelfUpdate() {
        assertFalse(
            shouldStartInAppSelfUpdate(
                selfUpdateEnabled = false,
                installedVersion = "2026.9.2",
                targetVersion = "2026.9.4",
            ),
        )
    }

    @Test
    fun installedVersionDoesNotSelfUpdate() {
        assertFalse(
            shouldStartInAppSelfUpdate(
                selfUpdateEnabled = true,
                installedVersion = "2026.9.2",
                targetVersion = "2026.9.2",
            ),
        )
    }

    @Test
    fun olderVersionDoesNotSelfUpdate() {
        assertFalse(
            shouldStartInAppSelfUpdate(
                selfUpdateEnabled = true,
                installedVersion = "2026.9.2",
                targetVersion = "2026.5.22",
            ),
        )
    }
}
