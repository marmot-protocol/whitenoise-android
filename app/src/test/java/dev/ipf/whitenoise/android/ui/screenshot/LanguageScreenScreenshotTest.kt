package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.LanguageScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the language destination: one radio group of every shipped locale under the settings frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class LanguageScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light rendering with the system default selected. */
    @Test
    fun languageLight() {
        render(darkTheme = false)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/language_screen_light.png")
    }

    /** AMOLED rendering keeps the white outlines and seams of the connected rows. */
    @Test
    fun languageAmoled() {
        render(darkTheme = true, amoled = true)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/language_screen_amoled.png")
    }

    /** Compose the production screen with an isolated app state and a no-op back callback. */
    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "missing-account",
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                LanguageScreen(appState = appState, onBack = {})
            }
        }
    }
}
