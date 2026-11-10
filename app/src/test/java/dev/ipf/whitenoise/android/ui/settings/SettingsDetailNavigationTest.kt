package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsDetailNavigationTest {
    @Test fun nestedSearchControlsRetainTheirOrdinaryRouteParents() {
        val catalog = settingsSearchCatalog().associateBy { it.id }
        assertEquals(SettingsDetail.About, catalog.getValue("About.privacy").destination)
        assertEquals(SettingsDetail.Help, settingsDetailParent(SettingsDetail.About))
        assertEquals(SettingsDetail.Appearance, catalog.getValue("Appearance.font_size").destination)
        assertNull(settingsDetailParent(SettingsDetail.Appearance))
    }

    /** Key Packages returns to Developer Tools, a hub destination; About returns to Help, which returns home. */
    @Test
    fun developerToolsAndHelpFormBackStacksDownToHome() {
        // Key Packages → Developer Tools → home (null); About → Help → home (null).
        assertEquals(SettingsDetail.Developer, settingsDetailParent(SettingsDetail.KeyPackages))
        assertEquals(SettingsDetail.HelpSupport, settingsDetailParent(SettingsDetail.Developer))
        assertNull(settingsDetailParent(SettingsDetail.HelpSupport))
        assertEquals(SettingsDetail.Help, settingsDetailParent(SettingsDetail.About))
        assertEquals(SettingsDetail.Help, settingsDetailParent(SettingsDetail.BugReport))
        assertEquals(SettingsDetail.HelpSupport, settingsDetailParent(SettingsDetail.Help))
    }

    @Test
    fun appearanceSubscreensReturnToAppearance() {
        assertEquals(SettingsDetail.Appearance, settingsDetailParent(SettingsDetail.ActionColor))
        assertEquals(SettingsDetail.Appearance, settingsDetailParent(SettingsDetail.ChatBubbleColors))
    }

    @Test
    fun chatsAndVoiceLanguageSubscreensReturnToTheirHubs() {
        assertEquals(SettingsDetail.Chats, settingsDetailParent(SettingsDetail.CustomEmoji))
        assertEquals(SettingsDetail.Chats, settingsDetailParent(SettingsDetail.Composer))
        assertEquals(SettingsDetail.Chats, settingsDetailParent(SettingsDetail.Gestures))
        assertEquals(SettingsDetail.VoiceLanguage, settingsDetailParent(SettingsDetail.Language))
        assertEquals(SettingsDetail.Account, settingsDetailParent(SettingsDetail.Relays))
        assertEquals(SettingsDetail.Notifications, settingsDetailParent(SettingsDetail.NotificationDelivery))
    }

    /** Diagnostics & Improvements returns to Privacy & Security, which returns home. */
    @Test
    fun diagnosticsReturnsToPrivacySecurity() {
        assertEquals(SettingsDetail.DevicePrivacy, settingsDetailParent(SettingsDetail.DiagnosticsImprovements))
        assertNull(settingsDetailParent(SettingsDetail.DevicePrivacy))
    }

    /** Manage storage returns to Data Usage, which returns home, and its search entries open it in place. */
    @Test
    fun manageStorageReturnsToDataUsage() {
        assertEquals(SettingsDetail.Data, settingsDetailParent(SettingsDetail.ManageStorage))
        assertNull(settingsDetailParent(SettingsDetail.Data))
        val catalog = settingsSearchCatalog().associateBy { it.id }
        assertEquals(SettingsDetail.Data, catalog.getValue("Data.manage_storage").destination)
        assertEquals(SettingsDetail.ManageStorage, catalog.getValue("ManageStorage.largest").destination)
        assertEquals(SettingsDetail.ManageStorage, catalog.getValue("ManageStorage.keep_media").destination)
    }

    @Test
    fun topLevelDetailsReturnToHome() {
        listOf(
            SettingsDetail.Account,
            SettingsDetail.AiAgents,
            SettingsDetail.DevicePrivacy,
            SettingsDetail.Notifications,
            SettingsDetail.Chats,
            SettingsDetail.VoiceLanguage,
            SettingsDetail.HelpSupport,
            SettingsDetail.Appearance,
        ).forEach { assertNull(settingsDetailParent(it)) }
    }
}
