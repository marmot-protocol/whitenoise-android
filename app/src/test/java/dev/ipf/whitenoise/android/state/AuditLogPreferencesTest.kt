package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.AuditDataModeFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditLogPreferencesTest {
    private val preferences
        get() =
            RuntimeEnvironment
                .getApplication()
                .applicationContext
                .getSharedPreferences("whitenoise-audit-test", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun redactionDefaultsOn() {
        assertTrue(with(AuditLogPreferences) { preferences.readRedactSensitiveData() })
    }

    @Test
    fun redactionRoundTripsThroughPreferences() {
        AuditLogPreferences.writeRedactSensitiveData(preferences, false)
        assertFalse(with(AuditLogPreferences) { preferences.readRedactSensitiveData() })

        AuditLogPreferences.writeRedactSensitiveData(preferences, true)
        assertTrue(with(AuditLogPreferences) { preferences.readRedactSensitiveData() })
    }

    @Test
    fun redactionOnMapsToObfuscatedSensitiveData() {
        val settings = AuditLogPreferences.settingsFor(enabled = true, redactSensitiveData = true)

        assertTrue(settings.enabled)
        assertEquals(AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA, settings.dataMode)
    }

    @Test
    fun redactionOffMapsToFullData() {
        val settings = AuditLogPreferences.settingsFor(enabled = true, redactSensitiveData = false)

        assertTrue(settings.enabled)
        assertEquals(AuditDataModeFfi.FULL_DATA, settings.dataMode)
    }

    @Test
    fun persistedModeSurvivesTogglingAuditLoggingOffAndOn() {
        AuditLogPreferences.writeRedactSensitiveData(preferences, false)
        val persisted = with(AuditLogPreferences) { preferences.readRedactSensitiveData() }

        val off = AuditLogPreferences.settingsFor(enabled = false, redactSensitiveData = persisted)
        assertFalse(off.enabled)
        assertEquals(AuditDataModeFfi.FULL_DATA, off.dataMode)

        val on = AuditLogPreferences.settingsFor(enabled = true, redactSensitiveData = persisted)
        assertTrue(on.enabled)
        assertEquals(AuditDataModeFfi.FULL_DATA, on.dataMode)
    }
}
