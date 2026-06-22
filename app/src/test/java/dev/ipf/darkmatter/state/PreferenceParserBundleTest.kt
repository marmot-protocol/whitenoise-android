package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + default-on-unknown coverage for the pure preference enums in
 * the `state/` package (#559 sub-task #E), mirroring `MediaQualityTest`. These
 * parsers back persisted SharedPreferences strings; a silent rename of a
 * `preferenceValue` or a regression in the unknown-value fallback would
 * silently reset a user's saved choice, so the contract is pinned here.
 *
 * The two Context-bound preference holders in scope #E
 * (`BackgroundConnectionPreferences`, `RecentEmojiPreferences`) read/write
 * Android `SharedPreferences` and so need Robolectric/instrumentation rather
 * than a plain JVM unit test; their pure helper (`RecentEmojiList`) is already
 * covered separately. This file covers the parsers that are pure today.
 */
class PreferenceParserBundleTest {
    // ---- AppThemeMode -------------------------------------------------------

    @Test
    fun appThemeMode_roundTripsEveryEntry() {
        AppThemeMode.entries.forEach { mode ->
            assertEquals(mode, AppThemeMode.fromPreference(mode.preferenceValue))
        }
    }

    @Test
    fun appThemeMode_preferenceValuesAreTheStableWireStrings() {
        // These strings are persisted; pin them so a rename is a visible diff.
        assertEquals("system", AppThemeMode.System.preferenceValue)
        assertEquals("light", AppThemeMode.Light.preferenceValue)
        assertEquals("dark", AppThemeMode.Dark.preferenceValue)
        assertEquals("amoled", AppThemeMode.Amoled.preferenceValue)
    }

    @Test
    fun appThemeMode_defaultsToSystemForNullOrUnknown() {
        assertEquals(AppThemeMode.System, AppThemeMode.fromPreference(null))
        assertEquals(AppThemeMode.System, AppThemeMode.fromPreference(""))
        assertEquals(AppThemeMode.System, AppThemeMode.fromPreference("garbage"))
        // Case matters: the stored value is exact-match, so a differently-cased
        // string falls back rather than matching.
        assertEquals(AppThemeMode.System, AppThemeMode.fromPreference("DARK"))
    }

    @Test
    fun appThemeMode_resolveDarkThemeFollowsTheModeNotTheSystem() {
        // System defers to the platform flag; the explicit modes ignore it.
        assertTrue(AppThemeMode.System.resolveDarkTheme(systemDarkTheme = true))
        assertFalse(AppThemeMode.System.resolveDarkTheme(systemDarkTheme = false))

        assertFalse(AppThemeMode.Light.resolveDarkTheme(systemDarkTheme = true))
        assertTrue(AppThemeMode.Dark.resolveDarkTheme(systemDarkTheme = false))
        assertTrue(AppThemeMode.Amoled.resolveDarkTheme(systemDarkTheme = false))
    }

    @Test
    fun appThemeMode_isAmoledOnlyForAmoled() {
        assertTrue(AppThemeMode.Amoled.isAmoled)
        assertFalse(AppThemeMode.System.isAmoled)
        assertFalse(AppThemeMode.Light.isAmoled)
        assertFalse(AppThemeMode.Dark.isAmoled)
    }

    // ---- EnterKeyBehavior ---------------------------------------------------

    @Test
    fun enterKeyBehavior_roundTripsEveryEntry() {
        EnterKeyBehavior.entries.forEach { behavior ->
            assertEquals(behavior, EnterKeyBehavior.fromPreference(behavior.preferenceValue))
        }
    }

    @Test
    fun enterKeyBehavior_preferenceValuesAreTheStableWireStrings() {
        assertEquals("send", EnterKeyBehavior.SendMessage.preferenceValue)
        assertEquals("newline", EnterKeyBehavior.NewLine.preferenceValue)
    }

    @Test
    fun enterKeyBehavior_defaultsToSendMessageForNullOrUnknown() {
        // SendMessage is the documented default (#404) -- matches most chat
        // composers; an unknown stored value must not flip the user to NewLine.
        assertEquals(EnterKeyBehavior.SendMessage, EnterKeyBehavior.fromPreference(null))
        assertEquals(EnterKeyBehavior.SendMessage, EnterKeyBehavior.fromPreference(""))
        assertEquals(EnterKeyBehavior.SendMessage, EnterKeyBehavior.fromPreference("garbage"))
        assertEquals(EnterKeyBehavior.SendMessage, EnterKeyBehavior.fromPreference("SEND"))
    }
}
