package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeStateTest {
    /** A self-updating build with an account shows every section in the prototype's order. */
    @Test
    fun selfUpdatingBuildIncludesEverySettingsSectionInDisplayOrder() {
        val state = settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = true)
        assertTrue(state.showProfileHeader)
        assertEquals(
            listOf(
                SettingsHomeSection.Profile,
                SettingsHomeSection.AppUpdates,
                SettingsHomeSection.Hub,
                SettingsHomeSection.Support,
                SettingsHomeSection.SignOut,
                SettingsHomeSection.Version,
            ),
            state.sections,
        )
    }

    /** The hub lists the prototype's eleven rows in its order; Key Packages lives under Developer tools. */
    @Test
    fun hubRowsFollowThePrototypeOrder() {
        val state = settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = false)
        assertEquals(
            listOf(
                SettingsHomeRow.Profile,
                SettingsHomeRow.ProfileKeys,
                SettingsHomeRow.AiAgents,
                SettingsHomeRow.Notifications,
                SettingsHomeRow.ReadAloud,
                SettingsHomeRow.Dictation,
                SettingsHomeRow.Appearance,
                SettingsHomeRow.ChatFolders,
                SettingsHomeRow.PrivacySecurity,
                SettingsHomeRow.DataUsage,
                SettingsHomeRow.Relays,
            ),
            state.hubRows,
        )
        assertEquals(
            listOf(
                SettingsHomeRow.Help,
                SettingsHomeRow.ChatWithSupport,
                SettingsHomeRow.Donate,
                SettingsHomeRow.DeveloperTools,
            ),
            state.supportRows,
        )
    }

    /** Without an account the profile header and sign out disappear; store builds also drop app updates. */
    @Test
    fun storeBuildWithoutActiveAccountHidesProfileSignOutAndUpdates() {
        val state = settingsHomeState(hasActiveAccount = false, selfUpdateEnabled = false)
        assertFalse(state.showProfileHeader)
        assertEquals(
            listOf(SettingsHomeSection.Hub, SettingsHomeSection.Support, SettingsHomeSection.Version),
            state.sections,
        )
    }

    /** Every hub row except Chat with support opens a settings detail. */
    @Test
    fun onlyChatWithSupportRunsAnActionInsteadOfOpeningADetail() {
        val actionRows = SettingsHomeRow.entries.filter { it.detail == null }
        assertEquals(listOf(SettingsHomeRow.ChatWithSupport), actionRows)
    }
}
