package dev.ipf.whitenoise.android

import android.graphics.Color
import dev.ipf.whitenoise.android.state.AppThemeMode

private const val PRE_COMPOSE_BACKGROUND_LIGHT = 0xFFECEEEE.toInt()
private const val PRE_COMPOSE_BACKGROUND_DARK = 0xFF0F1112.toInt()

internal fun preComposeThemeFor(
    themeMode: AppThemeMode,
    systemDarkTheme: Boolean,
): Int =
    when (themeMode) {
        AppThemeMode.System -> if (systemDarkTheme) R.style.Theme_WhiteNoise_Dark else R.style.Theme_WhiteNoise_Light
        AppThemeMode.Light -> R.style.Theme_WhiteNoise_Light
        AppThemeMode.Dark -> R.style.Theme_WhiteNoise_Dark
        AppThemeMode.Amoled -> R.style.Theme_WhiteNoise_Amoled
    }

internal fun preComposeWindowBackgroundFor(
    themeMode: AppThemeMode,
    systemDarkTheme: Boolean,
): Int =
    when (themeMode) {
        AppThemeMode.System -> if (systemDarkTheme) PRE_COMPOSE_BACKGROUND_DARK else PRE_COMPOSE_BACKGROUND_LIGHT
        AppThemeMode.Light -> PRE_COMPOSE_BACKGROUND_LIGHT
        AppThemeMode.Dark -> PRE_COMPOSE_BACKGROUND_DARK
        AppThemeMode.Amoled -> Color.BLACK
    }
