package dev.ipf.whitenoise.android.ui.screenshot

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeViewport
import dev.ipf.whitenoise.android.ui.settings.SettingsScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Settings destination at the start, middle and end of its draw-layer motion. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsRouteMotionScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = capture("light")

    @Test fun dark() = capture("dark", dark = true)

    @Test fun amoled() = capture("amoled", dark = true, amoled = true)

    @Test fun largeRtl() = capture("large_rtl", dark = true, amoled = true, rtl = true)

    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        val app: Application = ApplicationProvider.getApplicationContext()
        val appState =
            WhiteNoiseAppState(
                app,
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = null,
            )
        val detail = mutableStateOf<SettingsDetail?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    Surface {
                        SettingsScreen(
                            appState,
                            {},
                            {},
                            {},
                            detail.value,
                            { detail.value = it },
                            SettingsHomeViewport.Top,
                            {},
                        )
                    }
                }
            }
        }
        rule.runOnIdle { detail.value = SettingsDetail.Gestures }
        rule.mainClock.advanceTimeByFrame()
        rule.onRoot().captureRoboImage("src/test/snapshots/settings_motion_${name}_first.png")
        rule.mainClock.advanceTimeBy(96)
        rule.onRoot().captureRoboImage("src/test/snapshots/settings_motion_${name}_middle.png")
        rule.mainClock.advanceTimeBy(200)
        rule.onRoot().captureRoboImage("src/test/snapshots/settings_motion_${name}_settled.png")
    }
}
