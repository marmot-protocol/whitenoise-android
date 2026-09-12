package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsDetailNavigationTest {
    /** Key Packages returns to Developer Tools, a hub destination; About returns to Help, which returns home. */
    @Test
    fun developerToolsAndHelpFormBackStacksDownToHome() {
        // Key Packages → Developer Tools → home (null); About → Help → home (null).
        assertEquals(SettingsDetail.Developer, settingsDetailParent(SettingsDetail.KeyPackages))
        assertNull(settingsDetailParent(SettingsDetail.Developer))
        assertEquals(SettingsDetail.Help, settingsDetailParent(SettingsDetail.About))
        assertNull(settingsDetailParent(SettingsDetail.Help))
    }

    @Test
    fun appearanceSubscreensReturnToAppearance() {
        assertEquals(SettingsDetail.Appearance, settingsDetailParent(SettingsDetail.ActionColor))
        assertEquals(SettingsDetail.Appearance, settingsDetailParent(SettingsDetail.ChatBubbleColors))
    }

    /** Diagnostics & Improvements returns to Privacy & Security, which returns home. */
    @Test
    fun diagnosticsReturnsToPrivacySecurity() {
        assertEquals(SettingsDetail.DevicePrivacy, settingsDetailParent(SettingsDetail.DiagnosticsImprovements))
        assertNull(settingsDetailParent(SettingsDetail.DevicePrivacy))
    }

    @Test
    fun topLevelDetailsReturnToHome() {
        listOf(
            SettingsDetail.AccountKeys,
            SettingsDetail.AiAgents,
            SettingsDetail.DevicePrivacy,
            SettingsDetail.Relays,
            SettingsDetail.Notifications,
            SettingsDetail.Appearance,
        ).forEach { assertNull(settingsDetailParent(it)) }
    }
}
