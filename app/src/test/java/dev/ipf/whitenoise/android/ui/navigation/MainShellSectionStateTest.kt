package dev.ipf.whitenoise.android.ui.navigation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainShellSectionStateTest {
    @Test
    fun restoredPrivacyDestinationBelongsToTheSameAccountAndRuntime() {
        val savedState = SavedStateHandle()
        val first = MainShellSectionState(savedState)
        first.bind("personal", 4)
        first.dispatchSection(ShellNavEvent.OpenSettings(SettingsDetail.DevicePrivacy))

        val restored =
            MainShellSectionState(SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) }))
        restored.bind("personal", 4)

        assertEquals(MainSection.Settings.name, restored.sectionName)
        assertEquals(SettingsDetail.DevicePrivacy.name, restored.settingsDetailName)
    }

    /** The pages a Settings page was opened through, with an item argument, come back after process death. */
    @Test
    fun restoredSettingsPagesKeepTheirOpenersAndItems() {
        val savedState = SavedStateHandle()
        val first = MainShellSectionState(savedState)
        first.bind("personal", 4)
        first.dispatchSection(ShellNavEvent.OpenSettings(SettingsDetail.Support))
        val support = first.settingsStack.drop(1)
        first.dispatchSection(ShellNavEvent.ChangeSettingsDetail(SettingsDetail.SupportRelays, parents = support))
        val relays = first.settingsStack.drop(1)
        first.dispatchSection(
            ShellNavEvent.ChangeSettingsDetail(SettingsDetail.RelayDetails, "wss://relay.example", relays),
        )

        val restored =
            MainShellSectionState(SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) }))
        restored.bind("personal", 4)

        assertEquals(first.settingsStack, restored.settingsStack)
        assertEquals(ShellRoute.Settings(SettingsDetail.SupportRelays), restored.settingsStack.dropLast(1).last())
        assertEquals("wss://relay.example", restored.settingsStack.last().arg)
    }

    /** A saved page name that this build no longer knows drops the saved chain, never the saved page. */
    @Test
    fun anUnknownSavedPageFallsBackToTheFixedParents() {
        assertNull(decodeSettingsChain(listOf("Account", "NoSuchPage")))
        assertEquals(
            listOf(ShellRoute.Settings(SettingsDetail.Relays), ShellRoute.Settings(SettingsDetail.RelayDetails, "a=b")),
            decodeSettingsChain(listOf("Relays", "RelayDetails=a=b")),
        )
    }

    @Test
    fun accountReplacementCannotRestoreThePreviousAccountsDestination() {
        val route = privacyDestination()
        route.bind("work", 4)
        assertChats(route)
        route.bind("personal", 4)
        assertChats(route)
    }

    @Test
    fun runtimeReplacementDiscardsThePreviousDestination() {
        val route = privacyDestination()
        route.bind("personal", 5)
        assertChats(route)
    }

    @Test
    fun previousProcessCounterCanRestartWithoutPoppingTheSavedDestination() {
        val route =
            MainShellSectionState(
                SavedStateHandle(
                    mapOf(
                        "main_shell_section_account" to "personal",
                        "main_shell_section_runtime" to 13,
                        "main_shell_section_process" to "previous-process",
                        "main_shell_section_name" to MainSection.Settings.name,
                        "main_shell_section_detail" to SettingsDetail.DevicePrivacy.name,
                    ),
                ),
            )
        route.bind("personal", 1)
        assertEquals(MainSection.Settings.name, route.sectionName)
        assertEquals(SettingsDetail.DevicePrivacy.name, route.settingsDetailName)

        route.bind("personal", 2)
        assertChats(route)
    }

    @Test
    fun restoredHolderStillRejectsRuntimeReplacementWithinTheSameProcess() {
        val savedState = SavedStateHandle()
        val first = MainShellSectionState(savedState)
        first.bind("personal", 4)
        first.dispatchSection(ShellNavEvent.OpenSettings(SettingsDetail.DevicePrivacy))

        val restored =
            MainShellSectionState(SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) }))
        restored.bind("personal", 5)
        assertChats(restored)
    }

    @Test
    fun signedOutScopeCannotSaveOrRecoverAProtectedDestination() {
        val route = privacyDestination()
        route.bind(null, 4)
        route.dispatchSection(ShellNavEvent.OpenSettings(SettingsDetail.AccountKeys))
        assertChats(route)
        route.bind("personal", 4)
        assertChats(route)
    }

    @Test
    fun explicitReleaseClearsSavedDestinationAsWellAsVisibleState() {
        val savedState = SavedStateHandle()
        val route = MainShellSectionState(savedState)
        route.bind("personal", 4)
        route.dispatchSection(ShellNavEvent.OpenSettings(SettingsDetail.AccountKeys))
        route.clear()

        val restored = MainShellSectionState(savedState)
        restored.bind("personal", 4)
        assertChats(restored)
    }

    @Test
    fun malformedSavedRoutesCannotBecomeDestinations() {
        val route =
            MainShellSectionState(
                SavedStateHandle(
                    mapOf(
                        "main_shell_section_account" to "personal",
                        "main_shell_section_runtime" to 4,
                        "main_shell_section_name" to "UnknownSection",
                        "main_shell_section_detail" to listOf("AccountKeys"),
                    ),
                ),
            )
        route.bind("personal", 4)
        assertChats(route)
    }

    /**
     * Two holders over one process-retained navigator: the newer one takes the saving over, and clearing the older
     * one leaves the newer one saving. Once the newer one is cleared too, nothing writes into its saved state.
     */
    @Test
    fun aClearedHolderDetachesOnlyItsOwnPersistence() {
        val navigator = ShellNavigator()
        val olderSaved = SavedStateHandle()
        val older = MainShellSectionState(olderSaved, navigator)
        older.bind("personal", 4)
        val newerSaved = SavedStateHandle()
        val newer = MainShellSectionState(newerSaved, navigator)
        newer.bind("personal", 4)

        older.detach()
        newer.dispatchSection(ShellNavEvent.OpenSettings(SettingsDetail.Appearance))
        assertEquals(MainSection.Settings.name, newerSaved.get<String>("main_shell_section_name"))
        assertEquals(SettingsDetail.Appearance.name, newerSaved.get<String>("main_shell_section_detail"))
        val olderDetail = olderSaved.get<String>("main_shell_section_detail")
        assertNull("the older holder stopped saving when the newer one attached", olderDetail)

        newer.detach()
        navigator.dispatch(ShellNavEvent.ChangeSettingsDetail(SettingsDetail.Relays))
        assertEquals(SettingsDetail.Relays.name, newer.settingsDetailName)
        assertEquals(
            "a cleared holder's saved state is no longer written",
            SettingsDetail.Appearance.name,
            newerSaved.get<String>("main_shell_section_detail"),
        )
    }

    @Test
    fun savedDestinationWithoutOwnershipIsDiscarded() {
        val route =
            MainShellSectionState(
                SavedStateHandle(
                    mapOf(
                        "main_shell_section_name" to MainSection.Settings.name,
                        "main_shell_section_detail" to SettingsDetail.AccountKeys.name,
                    ),
                ),
            )
        route.bind("personal", 4)
        assertChats(route)
    }

    private fun privacyDestination(): MainShellSectionState =
        MainShellSectionState(SavedStateHandle()).apply {
            bind("personal", 4)
            dispatchSection(ShellNavEvent.OpenSettings(SettingsDetail.DevicePrivacy))
        }

    private fun assertChats(route: MainShellSectionState) {
        assertEquals(MainSection.Chats.name, route.sectionName)
        assertNull(route.settingsDetailName)
    }
}
