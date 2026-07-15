package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun defaultSettingsHomeUsesTheProposedRowOrder() {
        assertEquals(
            listOf(
                SettingsDetail.Notifications,
                SettingsDetail.DevicePrivacy,
                SettingsDetail.Data,
                SettingsDetail.Appearance,
                SettingsDetail.Relays,
                SettingsDetail.AccountKeys,
                SettingsDetail.Help,
            ),
            settingsHomeDetails(developerMode = false),
        )
    }

    @Test
    fun developerRowIsHiddenUntilDeveloperModeIsEnabled() {
        assertEquals(
            settingsHomeDetails(developerMode = false) + SettingsDetail.Developer,
            settingsHomeDetails(developerMode = true),
        )
    }

    @Test
    fun seventhAboutVersionTapUnlocksDeveloperMode() {
        assertFalse(shouldUnlockDeveloperMode(versionTapCount = 6))
        assertTrue(shouldUnlockDeveloperMode(versionTapCount = 7))
    }
}
