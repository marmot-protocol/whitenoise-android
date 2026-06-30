package dev.ipf.whitenoise.android

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.ContextThemeWrapper
import dev.ipf.whitenoise.android.state.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowBackgroundThemeTest {
    @Test
    fun launcherThemeUsesLightFallbackByDefault() {
        assertThemeFallback(
            style = R.style.Theme_WhiteNoise,
            expectedColor = PreComposeLightBackground,
            lightSystemBars = true,
        )
    }

    @Test
    fun launcherThemeUsesDarkFallbackInNightMode() {
        assertThemeFallback(
            style = R.style.Theme_WhiteNoise,
            expectedColor = PreComposeDarkBackground,
            lightSystemBars = false,
            nightMode = true,
        )
    }

    @Test
    fun explicitThemeVariantsMatchComposeFirstFrameSurface() {
        assertThemeFallback(
            style = R.style.Theme_WhiteNoise_Light,
            expectedColor = PreComposeLightBackground,
            lightSystemBars = true,
        )
        assertThemeFallback(
            style = R.style.Theme_WhiteNoise_Dark,
            expectedColor = PreComposeDarkBackground,
            lightSystemBars = false,
        )
        assertThemeFallback(
            style = R.style.Theme_WhiteNoise_Amoled,
            expectedColor = Color.BLACK,
            lightSystemBars = false,
        )
    }

    @Test
    fun persistedThemeModeSelectsMatchingFallbackTheme() {
        assertEquals(R.style.Theme_WhiteNoise_Light, preComposeThemeFor(AppThemeMode.System, systemDarkTheme = false))
        assertEquals(R.style.Theme_WhiteNoise_Dark, preComposeThemeFor(AppThemeMode.System, systemDarkTheme = true))
        assertEquals(R.style.Theme_WhiteNoise_Light, preComposeThemeFor(AppThemeMode.Light, systemDarkTheme = true))
        assertEquals(R.style.Theme_WhiteNoise_Dark, preComposeThemeFor(AppThemeMode.Dark, systemDarkTheme = false))
        assertEquals(R.style.Theme_WhiteNoise_Amoled, preComposeThemeFor(AppThemeMode.Amoled, systemDarkTheme = false))
    }

    @Test
    fun runtimeWindowBackgroundFollowsPersistedThemeMode() {
        assertEquals(PreComposeLightBackground, preComposeWindowBackgroundFor(AppThemeMode.System, systemDarkTheme = false))
        assertEquals(PreComposeDarkBackground, preComposeWindowBackgroundFor(AppThemeMode.System, systemDarkTheme = true))
        assertEquals(PreComposeLightBackground, preComposeWindowBackgroundFor(AppThemeMode.Light, systemDarkTheme = true))
        assertEquals(PreComposeDarkBackground, preComposeWindowBackgroundFor(AppThemeMode.Dark, systemDarkTheme = false))
        assertEquals(Color.BLACK, preComposeWindowBackgroundFor(AppThemeMode.Amoled, systemDarkTheme = false))
    }

    private fun assertThemeFallback(
        style: Int,
        expectedColor: Int,
        lightSystemBars: Boolean,
        nightMode: Boolean = false,
    ) {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication().withNightMode(nightMode), style)
        assertEquals(expectedColor, context.resolveColorAttr(android.R.attr.windowBackground))
        assertEquals(expectedColor, context.resolveColorAttr(android.R.attr.colorBackground))
        assertEquals(expectedColor, context.resolveColorAttr(android.R.attr.windowSplashScreenBackground))
        assertEquals(expectedColor, context.resolveColorAttr(android.R.attr.statusBarColor))
        assertEquals(expectedColor, context.resolveColorAttr(android.R.attr.navigationBarColor))
        if (lightSystemBars) {
            assertTrue(context.resolveBooleanAttr(android.R.attr.windowLightStatusBar))
            assertTrue(context.resolveBooleanAttr(android.R.attr.windowLightNavigationBar))
        } else {
            assertFalse(context.resolveBooleanAttr(android.R.attr.windowLightStatusBar))
            assertFalse(context.resolveBooleanAttr(android.R.attr.windowLightNavigationBar))
        }
    }

    private fun Context.resolveColorAttr(attr: Int): Int {
        val value = resolveAttr(attr)
        if (value.resourceId != 0) return getColor(value.resourceId)
        assertTrue(value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT)
        return value.data
    }

    private fun Context.resolveBooleanAttr(attr: Int): Boolean = resolveAttr(attr).data != 0

    private fun Context.withNightMode(nightMode: Boolean): Context {
        val nightFlag =
            if (nightMode) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
        val config = Configuration(resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightFlag
        return createConfigurationContext(config)
    }

    private fun Context.resolveAttr(attr: Int): TypedValue {
        val value = TypedValue()
        assertTrue(theme.resolveAttribute(attr, value, true))
        return value
    }

    private companion object {
        const val PreComposeLightBackground = 0xFFECEEEE.toInt()
        const val PreComposeDarkBackground = 0xFF0F1112.toInt()
    }
}
