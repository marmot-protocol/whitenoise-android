package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
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
 * Screenshot baselines for the Appearance settings screen.
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

    /** The restored AMOLED bubble destination stays readable at 200% in RTL. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h2400dp-mdpi")
    fun appearanceAmoledLargeRtl() {
        render(
            mode = AppThemeMode.Amoled,
            darkTheme = true,
            amoled = true,
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
        )
        capture("amoled_large_rtl")
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

    /** The actual local-only sample and its controls remain visible in the light palette. */
    @Test
    fun accessibilityPreviewLight() {
        render(mode = AppThemeMode.System, darkTheme = false)
        capturePreview("light")
    }

    /** The dark preview keeps synthetic bubbles separate from its local simulation controls. */
    @Test
    fun accessibilityPreviewDark() {
        render(mode = AppThemeMode.Dark, darkTheme = true)
        capturePreview("dark")
    }

    /** AMOLED controls and sample content remain legible at double font size in RTL. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h2400dp-mdpi")
    fun accessibilityPreviewAmoledLargeRtl() {
        render(mode = AppThemeMode.Amoled, darkTheme = true, amoled = true, fontScale = 2f, layoutDirection = LayoutDirection.Rtl)
        capturePreview("amoled_large_rtl")
    }

    /** Lazily compose the whole preview before capturing its production content and simulation controls. */
    private fun capturePreview(variant: String) {
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("appearance.accessibility.preview"))
        composeRule.onNodeWithTag("appearance.accessibility.preview")
            .captureRoboImage("src/test/snapshots/appearance_accessibility_preview_$variant.png")
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
                        onOpenLanguage = {},
                        onOpenCustomEmoji = {},
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
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "missing-account",
        )
    }
}
