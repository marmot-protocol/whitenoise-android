package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.SETTINGS_HOME_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeContent
import dev.ipf.whitenoise.android.ui.settings.SettingsSearchEligibility
import dev.ipf.whitenoise.android.ui.settings.prepareSettingsSearch
import dev.ipf.whitenoise.android.ui.settings.searchSettings
import dev.ipf.whitenoise.android.ui.settings.settingsHomeState
import dev.ipf.whitenoise.android.ui.settings.settingsSearchCatalog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Settings toolbar/field/result components; no painted/prototype UI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SettingsSearchScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun resultsLight() = capture("settings_search_results_light", "font", false)

    @Test fun resultsDark() = capture("settings_search_results_dark", "font", true)

    @Test fun resultsAmoled() = capture("settings_search_results_amoled", "font", true, amoled = true)

    @Test fun expandedEmpty() = capture("settings_search_expanded_empty", "", true)

    @Test fun noResults() = capture("settings_search_no_results", "no matching setting", false)

    @Test fun resultsRtl() = capture("settings_search_results_rtl", "font", true, rtl = true)

    @Test fun resultsLargeFont() = capture("settings_search_results_large_font", "font", false, fontScale = 2f)

    @Suppress("LongParameterList") // Theme and viewport dimensions of a single capture fixture.
    private fun capture(
        name: String,
        query: String,
        dark: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val documents = prepareSettingsSearch(settingsSearchCatalog(), SettingsSearchEligibility(true, true, amoled, true), context::getString)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    SettingsHomeContent(
                        state = settingsHomeState(false, false),
                        account = null,
                        profileCount = 0,
                        appUpdateInfo = AppUpdateInfo("1.0.0", null, null, null, null),
                        versionName = "1.0.0",
                        onBack = {},
                        onOpenShareConnect = {},
                        onAddProfile = {},
                        onSwitchProfile = {},
                        onOpenDetail = {},
                        onAppUpdateAction = {},
                        searchOpen = true,
                        searchQuery = query,
                        searchResults = searchSettings(documents, query),
                    )
                }
            }
        }
        composeRule.onNodeWithTag(SETTINGS_HOME_CONTENT_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }
}
