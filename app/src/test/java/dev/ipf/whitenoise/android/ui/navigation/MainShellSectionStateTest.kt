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
        first.sectionName = MainSection.Settings.name
        first.settingsDetailName = SettingsDetail.DevicePrivacy.name

        val restored =
            MainShellSectionState(SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) }))
        restored.bind("personal", 4)

        assertEquals(MainSection.Settings.name, restored.sectionName)
        assertEquals(SettingsDetail.DevicePrivacy.name, restored.settingsDetailName)
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
        first.sectionName = MainSection.Settings.name
        first.settingsDetailName = SettingsDetail.DevicePrivacy.name

        val restored =
            MainShellSectionState(SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) }))
        restored.bind("personal", 5)
        assertChats(restored)
    }

    @Test
    fun signedOutScopeCannotSaveOrRecoverAProtectedDestination() {
        val route = privacyDestination()
        route.bind(null, 4)
        route.sectionName = MainSection.Settings.name
        route.settingsDetailName = SettingsDetail.AccountKeys.name
        assertChats(route)
        route.bind("personal", 4)
        assertChats(route)
    }

    @Test
    fun explicitReleaseClearsSavedDestinationAsWellAsVisibleState() {
        val savedState = SavedStateHandle()
        val route = MainShellSectionState(savedState)
        route.bind("personal", 4)
        route.sectionName = MainSection.Settings.name
        route.settingsDetailName = SettingsDetail.AccountKeys.name
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
            sectionName = MainSection.Settings.name
            settingsDetailName = SettingsDetail.DevicePrivacy.name
        }

    private fun assertChats(route: MainShellSectionState) {
        assertEquals(MainSection.Chats.name, route.sectionName)
        assertNull(route.settingsDetailName)
    }
}
