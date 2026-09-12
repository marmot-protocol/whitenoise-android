package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.ActionColorScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Action colour editor with a saved blue accent, plus the fixed-colours notice AMOLED shows instead. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
class ActionColorScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Light editor with the saved accent selected and previewed. */
    @Test
    fun actionColorLight() = capture(AppThemeMode.Light, darkTheme = false, name = "action_color_light.png")

    /** Dark editor with the saved accent selected and previewed. */
    @Test
    fun actionColorDark() = capture(AppThemeMode.Dark, darkTheme = true, name = "action_color_dark.png")

    /** AMOLED replaces the editor with the fixed-colours notice. */
    @Test
    fun actionColorAmoled() = capture(AppThemeMode.Amoled, darkTheme = true, name = "action_color_amoled.png")

    /** Renders the screen for one theme mode and records the whole window. */
    private fun capture(
        mode: AppThemeMode,
        darkTheme: Boolean,
        name: String,
    ) {
        val appState = testAppState(mode)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = mode == AppThemeMode.Amoled) {
                ActionColorScreen(appState = appState, onBack = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name")
    }

    /** An active account on [mode] with a blue accent saved for the light and dark themes. */
    private fun testAppState(mode: AppThemeMode): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "alice",
        ).also {
            it.updateThemeMode(mode)
            it.updateActionColor(BubbleTheme.Light, SAVED_ACCENT)
            it.updateActionColor(BubbleTheme.Dark, SAVED_ACCENT)
        }

    private companion object {
        const val SAVED_ACCENT = 0xFF1D4ED8L
    }
}
