package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.AppearanceScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pre-change baselines for the Appearance settings screen before the M123 pilot restyles it.
 * The tall viewport keeps every group visible so the whole screen is one comparable frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class AppearanceScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Default light rendering with the System theme card selected. */
    @Test
    fun appearanceLight() {
        render(mode = AppThemeMode.System, darkTheme = false)
        capture("light")
    }

    /** Dark rendering with the Dark theme card selected. */
    @Test
    fun appearanceDark() {
        render(mode = AppThemeMode.Dark, darkTheme = true)
        capture("dark")
    }

    /** The current Amoled palette with its outlined group and the AMOLED card selected. */
    @Test
    fun appearanceAmoled() {
        render(mode = AppThemeMode.Amoled, darkTheme = true, amoled = true)
        capture("amoled")
    }

    /** Titles, subtitles and the long colour descriptions at 200% font scale. */
    @Test
    fun appearanceLargeFont() {
        render(mode = AppThemeMode.System, darkTheme = false, fontScale = 2f)
        capture("large_font")
    }

    /** Right-to-left mirroring of icons, radio buttons and text alignment. */
    @Test
    fun appearanceRtl() {
        render(mode = AppThemeMode.System, darkTheme = false, layoutDirection = LayoutDirection.Rtl)
        capture("rtl")
    }

    /** Compose the production screen with an isolated app state and no-op navigation callbacks. */
    private fun render(
        mode: AppThemeMode,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val appState = appearanceAppState().also { it.updateThemeMode(mode) }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    AppearanceScreen(
                        appState = appState,
                        onBack = {},
                        onOpenActionColor = {},
                        onOpenChatBubbleColors = {},
                    )
                }
            }
        }
    }

    /** Capture the whole window so the top bar, both groups and their spacing are all pinned. */
    private fun capture(variant: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/appearance_screen_$variant.png")
    }

    /** An app state whose appearance preferences start from defaults regardless of earlier tests. */
    private fun appearanceAppState(): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE).edit().clear().commit()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "missing-account",
        )
    }
}
