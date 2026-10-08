package dev.ipf.whitenoise.android.maestro

import androidx.appcompat.app.AppCompatDelegate
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Read the actual presentation owner on its UI dispatcher and allow bounded state convergence. */
internal suspend fun verifyMaestroPreferences(
    state: WhiteNoiseAppState,
    postcondition: String,
) {
    withTimeout(15_000L) {
        while (true) {
            val matches =
                withContext(Dispatchers.Main.immediate) {
                    when (postcondition) {
                        "light" -> state.themeMode == AppThemeMode.Light
                        "dark" -> state.themeMode == AppThemeMode.Dark
                        "amoled" -> state.themeMode == AppThemeMode.Amoled
                        "font-large" -> state.fontScale == AppFontScale.Large
                        "language-system" ->
                            state.languageTag.isEmpty() &&
                                AppCompatDelegate.getApplicationLocales().toLanguageTags().isEmpty()
                        else -> error("Unknown presentation postcondition")
                    }
                }
            if (matches) return@withTimeout
            delay(100L)
        }
    }
}
