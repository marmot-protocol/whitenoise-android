package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeStateTest {
    /** A self-updating build with an account shows every section in display order, with three labelled groups. */
    @Test
    fun selfUpdatingBuildIncludesEverySettingsSectionInDisplayOrder() {
        val state = settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = true)
        assertTrue(state.showProfileHeader)
        assertEquals(
            listOf(
                SettingsHomeSection.Profile,
                SettingsHomeSection.AppUpdates,
                SettingsHomeSection.Account,
                SettingsHomeSection.AppPreferences,
                SettingsHomeSection.Support,
                SettingsHomeSection.SignOut,
                SettingsHomeSection.Version,
            ),
            state.sections,
        )
    }

    /** Account holds the identity rows, App the preferences, Support the help rows, each under its own heading. */
    @Test
    fun groupsSplitTheRowsUnderAccountAppAndSupportHeadings() {
        val state = settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = false)
        assertEquals(
            listOf(
                SettingsHomeGroup(
                    section = SettingsHomeSection.Account,
                    titleRes = R.string.account,
                    rows = listOf(SettingsHomeRow.Profile, SettingsHomeRow.ProfileKeys, SettingsHomeRow.Relays),
                ),
                SettingsHomeGroup(
                    section = SettingsHomeSection.AppPreferences,
                    titleRes = R.string.app_preferences,
                    rows =
                        listOf(
                            SettingsHomeRow.Appearance,
                            SettingsHomeRow.ChatFolders,
                            SettingsHomeRow.Notifications,
                            SettingsHomeRow.ReadAloud,
                            SettingsHomeRow.Dictation,
                            SettingsHomeRow.DataUsage,
                            SettingsHomeRow.PrivacySecurity,
                            SettingsHomeRow.AiAgents,
                        ),
                ),
                SettingsHomeGroup(
                    section = SettingsHomeSection.Support,
                    titleRes = R.string.support,
                    rows =
                        listOf(
                            SettingsHomeRow.Help,
                            SettingsHomeRow.ChatWithSupport,
                            SettingsHomeRow.Donate,
                            SettingsHomeRow.DeveloperTools,
                        ),
                ),
            ),
            state.groups,
        )
    }

    /** Every row belongs to exactly one group and no group is empty, so nothing falls out of the home. */
    @Test
    fun everyRowAppearsInExactlyOneGroup() {
        val state = settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = false)
        assertEquals(SettingsHomeRow.entries, state.groups.flatMap { it.rows })
        assertTrue(state.groups.none { it.rows.isEmpty() })
        assertEquals(state.groups.map { it.section }, state.sections.filter { it.groupTitleRes != null })
    }

    /** Without an account the profile header and sign out disappear; store builds also drop app updates. */
    @Test
    fun storeBuildWithoutActiveAccountHidesProfileSignOutAndUpdates() {
        val state = settingsHomeState(hasActiveAccount = false, selfUpdateEnabled = false)
        assertFalse(state.showProfileHeader)
        assertEquals(
            listOf(
                SettingsHomeSection.Account,
                SettingsHomeSection.AppPreferences,
                SettingsHomeSection.Support,
                SettingsHomeSection.Version,
            ),
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
