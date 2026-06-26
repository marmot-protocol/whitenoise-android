package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {
    @Test
    fun themeModeResolvesAgainstSystemTheme() {
        assertTrue(AppThemeMode.System.resolveDarkTheme(systemDarkTheme = true))
        assertFalse(AppThemeMode.System.resolveDarkTheme(systemDarkTheme = false))
        assertFalse(AppThemeMode.Light.resolveDarkTheme(systemDarkTheme = true))
        assertTrue(AppThemeMode.Dark.resolveDarkTheme(systemDarkTheme = false))
    }

    @Test
    fun themeModeFallsBackToSystemForUnknownPreferences() {
        assertEquals(AppThemeMode.System, AppThemeMode.fromPreference(null))
        assertEquals(AppThemeMode.System, AppThemeMode.fromPreference("unknown"))
        assertEquals(AppThemeMode.Dark, AppThemeMode.fromPreference("dark"))
    }
}
