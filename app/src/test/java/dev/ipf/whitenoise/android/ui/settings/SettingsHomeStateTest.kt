package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeStateTest {
    /** Verifies Dictation appears in the complete account-aware settings order. */
    @Test
    fun selfUpdatingBuildIncludesEverySettingsSectionInDisplayOrder() {
        val state = settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = true)

        assertTrue(state.showAccountHeader)
        assertEquals(
            listOf(
                SettingsHomeSection.Account,
                SettingsHomeSection.AppPreferences,
                SettingsHomeSection.Support,
                SettingsHomeSection.AppUpdates,
                SettingsHomeSection.BuildInfo,
            ),
            state.sections,
        )
        assertEquals(
            listOf(
                SettingsHomeRow.Profile,
                SettingsHomeRow.AccountAndKeys,
                SettingsHomeRow.Relays,
                SettingsHomeRow.KeyPackages,
            ),
            state.accountRows,
        )
        assertEquals(
            listOf(
                SettingsHomeRow.Appearance,
                SettingsHomeRow.ChatFolders,
                SettingsHomeRow.DataAndStorage,
                SettingsHomeRow.Notifications,
                SettingsHomeRow.TextToSpeech,
                SettingsHomeRow.Dictation,
                SettingsHomeRow.DevicePrivacy,
                SettingsHomeRow.AiAgents,
                SettingsHomeRow.Help,
            ),
            state.preferenceRows,
        )
    }

    @Test
    fun storeBuildWithoutActiveAccountHidesOnlyDerivedAccountHeaderAndUpdateSection() {
        val state = settingsHomeState(hasActiveAccount = false, selfUpdateEnabled = false)

        assertFalse(state.showAccountHeader)
        assertFalse(SettingsHomeSection.AppUpdates in state.sections)
        assertTrue(SettingsHomeSection.Account in state.sections)
        assertEquals(SettingsHomeSection.BuildInfo, state.sections.last())
    }
}
