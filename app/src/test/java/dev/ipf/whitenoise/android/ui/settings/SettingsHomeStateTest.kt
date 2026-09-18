package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeStateTest {
    /** An update worth installing leads the screen, above the labelled groups. */
    @Test
    fun anAvailableUpdateLeadsTheSettingsSections() {
        val state =
            settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = true, updateAvailable = true)
        assertTrue(state.showProfileHeader)
        assertEquals(
            listOf(
                SettingsHomeSection.Profile,
                SettingsHomeSection.AppUpdateAvailable,
                SettingsHomeSection.Account,
                SettingsHomeSection.AppPreferences,
                SettingsHomeSection.Support,
                SettingsHomeSection.SignOut,
                SettingsHomeSection.Version,
            ),
            state.sections,
        )
    }

    /** With nothing to install the check keeps its place beside the version rather than leading. */
    @Test
    fun withNoUpdateTheCheckSitsAboveTheVersion() {
        val state =
            settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = true, updateAvailable = false)
        assertEquals(
            listOf(
                SettingsHomeSection.Profile,
                SettingsHomeSection.Account,
                SettingsHomeSection.AppPreferences,
                SettingsHomeSection.Support,
                SettingsHomeSection.SignOut,
                SettingsHomeSection.AppUpdateControls,
                SettingsHomeSection.Version,
            ),
            state.sections,
        )
    }

    /** A store-managed build owns its own updates, so neither placement appears at all. */
    @Test
    fun aStoreManagedBuildShowsNeitherUpdateSection() {
        for (available in listOf(false, true)) {
            val state =
                settingsHomeState(
                    hasActiveAccount = true,
                    selfUpdateEnabled = false,
                    updateAvailable = available,
                )
            assertFalse(
                "a store build must not offer its own update path (available=$available)",
                state.sections.any {
                    it == SettingsHomeSection.AppUpdateAvailable || it == SettingsHomeSection.AppUpdateControls
                },
            )
        }
    }

    /** Without an account the update placement is unchanged: it does not depend on who is signed in. */
    @Test
    fun theUpdatePlacementDoesNotDependOnAnAccount() {
        val signedOut =
            settingsHomeState(hasActiveAccount = false, selfUpdateEnabled = true, updateAvailable = true)
        assertEquals(SettingsHomeSection.AppUpdateAvailable, signedOut.sections.first())
        val current =
            settingsHomeState(hasActiveAccount = false, selfUpdateEnabled = true, updateAvailable = false)
        assertEquals(
            listOf(SettingsHomeSection.AppUpdateControls, SettingsHomeSection.Version),
            current.sections.takeLast(2),
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
