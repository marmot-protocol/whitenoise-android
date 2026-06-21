package dev.ipf.darkmatter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNavigationStateTest {
    @Test
    fun diagnosticsOpenedFromSecurityPrivacyStoresSecurityPrivacyAsBackTarget() {
        val destination = openDiagnosticsDestination(SettingsDetail.SecurityPrivacy)

        assertEquals(MainSection.Diagnostics.name, destination.sectionName)
        assertEquals(SettingsDetail.SecurityPrivacy.name, destination.settingsDetailName)
    }

    @Test
    fun diagnosticsBackReturnsToStoredSecurityPrivacyDetail() {
        val destination = diagnosticsBackDestination(SettingsDetail.SecurityPrivacy.name)

        assertEquals(MainSection.Settings.name, destination.sectionName)
        assertEquals(SettingsDetail.SecurityPrivacy.name, destination.settingsDetailName)
    }

    @Test
    fun diagnosticsBackWithoutStoredParentReturnsToSettingsHome() {
        val destination = diagnosticsBackDestination(null)

        assertEquals(MainSection.Settings.name, destination.sectionName)
        assertNull(destination.settingsDetailName)
    }
}
