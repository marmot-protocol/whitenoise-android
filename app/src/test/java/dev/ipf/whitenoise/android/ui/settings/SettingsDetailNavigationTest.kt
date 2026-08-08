package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsDetailNavigationTest {
    @Test
    fun helpAboutDeveloperFormABackStackDownToHome() {
        // Developer → About → Help → home (null).
        assertEquals(SettingsDetail.About, settingsDetailParent(SettingsDetail.Developer))
        assertEquals(SettingsDetail.Help, settingsDetailParent(SettingsDetail.About))
        assertNull(settingsDetailParent(SettingsDetail.Help))
    }

    @Test
    fun appearanceSubscreensReturnToAppearance() {
        assertEquals(SettingsDetail.Appearance, settingsDetailParent(SettingsDetail.ActionColor))
        assertEquals(SettingsDetail.Appearance, settingsDetailParent(SettingsDetail.ChatBubbleColors))
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
