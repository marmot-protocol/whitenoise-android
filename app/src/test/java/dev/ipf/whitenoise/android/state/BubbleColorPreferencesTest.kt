package dev.ipf.whitenoise.android.state

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BubbleColorPreferencesTest {
    private val preferences
        get() =
            RuntimeEnvironment
                .getApplication()
                .applicationContext
                .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun themeSlotResolvesSystemAgainstPlatformTheme() {
        assertEquals(BubbleTheme.Light, BubbleTheme.resolve(AppThemeMode.Light, systemDarkTheme = true))
        assertEquals(BubbleTheme.Dark, BubbleTheme.resolve(AppThemeMode.Dark, systemDarkTheme = false))
        assertEquals(BubbleTheme.Amoled, BubbleTheme.resolve(AppThemeMode.Amoled, systemDarkTheme = false))
        assertEquals(BubbleTheme.Light, BubbleTheme.resolve(AppThemeMode.System, systemDarkTheme = false))
        assertEquals(BubbleTheme.Dark, BubbleTheme.resolve(AppThemeMode.System, systemDarkTheme = true))
    }

    @Test
    fun globalColorsPersistIndependentlyPerThemeAndSide() {
        assertNull(BubbleColorPreferences.readGlobalColor(preferences, BubbleTheme.Light, BubbleSide.Mine))

        BubbleColorPreferences.writeGlobalColor(preferences, BubbleTheme.Light, BubbleSide.Mine, 0xFF112233)
        BubbleColorPreferences.writeGlobalColor(preferences, BubbleTheme.Dark, BubbleSide.Other, 0xFF445566)

        assertEquals(0xFF112233, BubbleColorPreferences.readGlobalColor(preferences, BubbleTheme.Light, BubbleSide.Mine))
        assertNull(BubbleColorPreferences.readGlobalColor(preferences, BubbleTheme.Light, BubbleSide.Other))
        assertNull(BubbleColorPreferences.readGlobalColor(preferences, BubbleTheme.Dark, BubbleSide.Mine))
        assertEquals(0xFF445566, BubbleColorPreferences.readGlobalColor(preferences, BubbleTheme.Dark, BubbleSide.Other))
    }

    @Test
    fun clearingGlobalColorRestoresAbsentSentinel() {
        BubbleColorPreferences.writeGlobalColor(preferences, BubbleTheme.Amoled, BubbleSide.Mine, 0xFF010203)
        BubbleColorPreferences.writeGlobalColor(preferences, BubbleTheme.Amoled, BubbleSide.Mine, null)

        assertNull(BubbleColorPreferences.readGlobalColor(preferences, BubbleTheme.Amoled, BubbleSide.Mine))
    }

    @Test
    fun chatColorsAreLocalToAccountGroupAndSide() {
        BubbleColorPreferences.writeChatColor(preferences, " account-a ", " GROUP-A ", BubbleSide.Mine, 0xFFABCDEF)

        assertEquals(0xFFABCDEF, BubbleColorPreferences.readChatColor(preferences, "account-a", "group-a", BubbleSide.Mine))
        assertNull(BubbleColorPreferences.readChatColor(preferences, "account-a", "group-a", BubbleSide.Other))
        assertNull(BubbleColorPreferences.readChatColor(preferences, "account-a", "group-b", BubbleSide.Mine))
        assertNull(BubbleColorPreferences.readChatColor(preferences, "account-b", "group-a", BubbleSide.Mine))
    }

    @Test
    fun clearingChatColorFallsBackToGlobalResolver() {
        BubbleColorPreferences.writeChatColor(preferences, "account-a", "group-a", BubbleSide.Other, 0xFFABCDEF)
        BubbleColorPreferences.writeChatColor(preferences, "account-a", "group-a", BubbleSide.Other, null)

        val resolved =
            resolveBubbleColorArgb(
                chatOverrideArgb = BubbleColorPreferences.readChatColor(preferences, "account-a", "group-a", BubbleSide.Other),
                globalOverrideArgb = 0xFF123456,
                defaultArgb = 0xFF654321,
            )

        assertEquals(0xFF123456, resolved)
    }

    @Test
    fun resolverPrefersChatThenGlobalThenThemeDefault() {
        assertEquals(0xFF111111, resolveBubbleColorArgb(0xFF111111, 0xFF222222, 0xFF333333))
        assertEquals(0xFF222222, resolveBubbleColorArgb(null, 0xFF222222, 0xFF333333))
        assertEquals(0xFF333333, resolveBubbleColorArgb(null, null, 0xFF333333))
    }

    @Test
    fun customHexParserAcceptsOpaqueRgbOnly() {
        assertEquals(0xFFA1B2C3, parseOpaqueColorHex("#A1b2C3"))
        assertEquals(0xFF00FF11, parseOpaqueColorHex(" 00ff11 "))
        assertNull(parseOpaqueColorHex("#123"))
        assertNull(parseOpaqueColorHex("#80112233"))
        assertNull(parseOpaqueColorHex("not-a-color"))
    }

    @Test
    fun readableTextAlwaysMeetsWcagAaForOpaqueCustomColors() {
        listOf(0xFF000000, 0xFFFFFFFF, 0xFF777777, 0xFF06B6D4).forEach { background ->
            val foreground = readableTextArgb(background)
            assertTrue(
                "foreground ${foreground?.toString(16)} on ${background.toString(16)} must meet WCAG AA",
                foreground != null && contrastRatio(foreground, background) >= WCAG_AA_NORMAL_TEXT_CONTRAST,
            )
        }
    }

    @Test
    fun tonalPresetsStayCuratedAndReadableWithDuplicateMaterialRole() {
        val presets =
            tonalBubbleColorPresets(
                primaryContainerArgb = 0xFF06B6D4,
                secondaryContainerArgb = 0xFF1E3A40,
                tertiaryContainerArgb = 0xFF1E3A40,
                errorContainerArgb = 0xFF5C1A1A,
                inversePrimaryArgb = 0xFF67E8F9,
                surfaceArgb = OPAQUE_BLACK_ARGB,
            )

        assertTrue("expected 8-12 presets, got ${presets.size}", presets.size in 8..12)
        assertEquals(presets.size, presets.distinct().size)
        assertTrue(presets.all { readableTextArgb(it) != null })
    }
}
