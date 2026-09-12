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
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.NotificationsScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Notifications screen without an account: delivery and background rows disabled, categories listed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class NotificationsScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** Light theme. */
    @Test
    fun notificationsLight() = capture("notifications_light", darkTheme = false)

    /** Dark theme. */
    @Test
    fun notificationsDark() = capture("notifications_dark", darkTheme = true)

    /** AMOLED: outlined groups. */
    @Test
    fun notificationsAmoled() = capture("notifications_amoled", darkTheme = true, amoled = true)

    /** RTL at 200 %: wrapping subtitles and mirrored switches. */
    @Test
    fun notificationsRtlLargeFont() {
        capture(
            "notifications_rtl_large_font",
            darkTheme = false,
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
        )
    }

    private fun capture(
        name: String,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "missing-account",
            )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    NotificationsScreen(appState = appState, onBack = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
