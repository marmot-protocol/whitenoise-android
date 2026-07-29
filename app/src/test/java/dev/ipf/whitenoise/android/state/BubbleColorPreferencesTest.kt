package dev.ipf.whitenoise.android.state

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun legacyGlobalColorsPersistIndependentlyPerThemeAndSide() {
        assertNull(BubbleColorPreferences.readLegacyGlobalColor(preferences, BubbleTheme.Light, BubbleSide.Mine))

        BubbleColorPreferences.writeLegacyGlobalColor(preferences, BubbleTheme.Light, BubbleSide.Mine, 0xFF112233)
        BubbleColorPreferences.writeLegacyGlobalColor(preferences, BubbleTheme.Dark, BubbleSide.Other, 0xFF445566)

        assertEquals(
            0xFF112233,
            BubbleColorPreferences.readLegacyGlobalColor(preferences, BubbleTheme.Light, BubbleSide.Mine),
        )
        assertNull(BubbleColorPreferences.readLegacyGlobalColor(preferences, BubbleTheme.Light, BubbleSide.Other))
        assertNull(BubbleColorPreferences.readLegacyGlobalColor(preferences, BubbleTheme.Dark, BubbleSide.Mine))
        assertEquals(
            0xFF445566,
            BubbleColorPreferences.readLegacyGlobalColor(preferences, BubbleTheme.Dark, BubbleSide.Other),
        )
    }

    @Test
    fun globalColorsAreLocalToAccountThemeAndSide() {
        BubbleColorPreferences.writeGlobalColor(
            preferences,
            accountRef = "account-a",
            theme = BubbleTheme.Light,
            side = BubbleSide.Mine,
            argb = 0xFF112233,
        )

        assertEquals(
            0xFF112233,
            BubbleColorPreferences.readGlobalColor(
                preferences,
                accountRef = "account-a",
                theme = BubbleTheme.Light,
                side = BubbleSide.Mine,
            ),
        )
        assertNull(
            BubbleColorPreferences.readGlobalColor(
                preferences,
                accountRef = "account-b",
                theme = BubbleTheme.Light,
                side = BubbleSide.Mine,
            ),
        )
    }

    @Test
    fun legacyGlobalColorsMigrateToExistingAccountsWithoutOverwritingScopedValues() {
        BubbleColorPreferences.writeLegacyGlobalColor(
            preferences,
            theme = BubbleTheme.Light,
            side = BubbleSide.Mine,
            argb = 0xFF112233,
        )
        BubbleColorPreferences.writeGlobalColor(
            preferences,
            accountRef = "account-b",
            theme = BubbleTheme.Light,
            side = BubbleSide.Mine,
            argb = 0xFF445566,
        )

        assertTrue(
            LegacyBubbleColorMigration.migrate(
                preferences = preferences,
                accountRefs = listOf(" account-a ", "account-b", "account-a", ""),
            ),
        )

        assertEquals(
            0xFF112233,
            BubbleColorPreferences.readGlobalColor(
                preferences,
                accountRef = "account-a",
                theme = BubbleTheme.Light,
                side = BubbleSide.Mine,
            ),
        )
        assertEquals(
            0xFF445566,
            BubbleColorPreferences.readGlobalColor(
                preferences,
                accountRef = "account-b",
                theme = BubbleTheme.Light,
                side = BubbleSide.Mine,
            ),
        )
        assertNull(BubbleColorPreferences.readLegacyGlobalColor(preferences, BubbleTheme.Light, BubbleSide.Mine))
    }

    @Test
    fun legacyGlobalColorsAreConsumedWhenThereAreNoExistingAccounts() {
        BubbleColorPreferences.writeLegacyGlobalColor(
            preferences,
            theme = BubbleTheme.Amoled,
            side = BubbleSide.Other,
            argb = 0xFF112233,
        )

        assertTrue(LegacyBubbleColorMigration.migrate(preferences, emptyList()))

        assertNull(BubbleColorPreferences.readLegacyGlobalColor(preferences, BubbleTheme.Amoled, BubbleSide.Other))
        assertFalse(LegacyBubbleColorMigration.migrate(preferences, emptyList()))
        assertNull(
            BubbleColorPreferences.readGlobalColor(
                preferences,
                accountRef = "created-later",
                theme = BubbleTheme.Amoled,
                side = BubbleSide.Other,
            ),
        )
    }

    @Test
    fun clearingGlobalColorRestoresAbsentSentinel() {
        BubbleColorPreferences.writeGlobalColor(
            preferences,
            "account-a",
            BubbleTheme.Amoled,
            BubbleSide.Mine,
            0xFF010203,
        )
        BubbleColorPreferences.writeGlobalColor(preferences, "account-a", BubbleTheme.Amoled, BubbleSide.Mine, null)

        assertNull(
            BubbleColorPreferences.readGlobalColor(
                preferences,
                "account-a",
                BubbleTheme.Amoled,
                BubbleSide.Mine,
            ),
        )
    }

    @Test
    fun actionColorsPersistAndResetPerAccountAndTheme() {
        ActionColorPreferences.writeColor(preferences, "account-a", BubbleTheme.Light, 0xFF112233)
        ActionColorPreferences.writeColor(preferences, "account-b", BubbleTheme.Light, 0xFF445566)
        ActionColorPreferences.writeColor(preferences, "account-a", BubbleTheme.Dark, 0xFF778899)

        assertEquals(0xFF112233, ActionColorPreferences.readColor(preferences, "account-a", BubbleTheme.Light))
        assertEquals(0xFF445566, ActionColorPreferences.readColor(preferences, "account-b", BubbleTheme.Light))
        assertEquals(0xFF778899, ActionColorPreferences.readColor(preferences, "account-a", BubbleTheme.Dark))
        assertNull(ActionColorPreferences.readColor(preferences, "account-b", BubbleTheme.Dark))

        ActionColorPreferences.writeColor(preferences, "account-a", BubbleTheme.Light, null)

        assertNull(ActionColorPreferences.readColor(preferences, "account-a", BubbleTheme.Light))
        assertEquals(0xFF445566, ActionColorPreferences.readColor(preferences, "account-b", BubbleTheme.Light))
    }

    @Test
    fun actionColorResolverUsesReadableForegroundAndFallsBackAsAPair() {
        assertEquals(
            ActionColorArgb(container = 0xFFFFFFFF, content = OPAQUE_BLACK_ARGB),
            resolveActionColorArgb(
                customArgb = 0xFFFFFFFF,
                defaultContainerArgb = 0xFF112233,
                defaultContentArgb = 0xFF445566,
            ),
        )
        assertEquals(
            ActionColorArgb(container = 0xFF000000, content = OPAQUE_WHITE_ARGB),
            resolveActionColorArgb(
                customArgb = 0xFF000000,
                defaultContainerArgb = 0xFF112233,
                defaultContentArgb = 0xFF445566,
            ),
        )
        assertEquals(
            ActionColorArgb(container = 0xFF112233, content = 0xFF445566),
            resolveActionColorArgb(
                customArgb = null,
                defaultContainerArgb = 0xFF112233,
                defaultContentArgb = 0xFF445566,
            ),
        )
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
    fun tonalPresetsIncludePureBlackAndWhite() {
        val presets = tonalBubbleColorPresets()

        assertTrue(presets.contains(OPAQUE_BLACK_ARGB))
        assertTrue(presets.contains(OPAQUE_WHITE_ARGB))
    }

    @Test
    fun tonalPresetsMatchDocumentedGoldenPaletteContract() {
        val presets = tonalBubbleColorPresets()

        assertTrue("expected 8-12 presets, got ${presets.size}", presets.size in 8..12)
        assertEquals(
            listOf(
                OPAQUE_BLACK_ARGB,
                OPAQUE_WHITE_ARGB,
                0xFFB91C1CL,
                0xFFC2410CL,
                0xFFA16207L,
                0xFF15803DL,
                0xFF0E7490L,
                0xFF1D4ED8L,
                0xFF6D28D9L,
                0xFFBE185DL,
            ),
            presets,
        )
        presets.forEach { background ->
            val foreground = readableTextArgb(background)
            assertTrue(
                "foreground ${foreground?.toString(16)} on ${background.toString(16)} must meet WCAG AA",
                foreground != null && contrastRatio(foreground, background) >= WCAG_AA_NORMAL_TEXT_CONTRAST,
            )
        }
    }
}
