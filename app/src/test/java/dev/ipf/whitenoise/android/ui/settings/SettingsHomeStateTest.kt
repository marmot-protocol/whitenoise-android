package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeStateTest {
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
                SettingsHomeRow.IdentityAndKeys,
                SettingsHomeRow.Relays,
                SettingsHomeRow.KeyPackages,
            ),
            state.accountRows,
        )
        assertEquals(
            listOf(
                SettingsHomeRow.Appearance,
                SettingsHomeRow.DataAndStorage,
                SettingsHomeRow.Notifications,
                SettingsHomeRow.TextToSpeech,
                SettingsHomeRow.SecurityAndPrivacy,
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
