package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.privacySafeErrorPresentation
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchBodyStage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real inline body stage leaves a local name visible in pending and failed states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GlobalSearchBodyStageScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun pendingLight() = render("pending_light", false, false)

    @Test fun pendingDark() = render("pending_dark", true, false)

    @Test fun failureLight() = render("failure_light", false, true)

    @Test fun failureDark() = render("failure_dark", true, true)

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun pendingLargeRtl() = render("pending_large_rtl", false, false, true)

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun failureLargeRtl() = render("failure_large_rtl", false, true, true)

    private fun render(
        name: String,
        dark: Boolean,
        failed: Boolean,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(
                        modifier =
                            Modifier.width(if (largeRtl) 320.dp else 360.dp).height(680.dp).testTag("body-stage"),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Text("Local group name")
                            GlobalSearchBodyStage(
                                loading = !failed,
                                failure =
                                    if (failed) {
                                        privacySafeErrorPresentation(
                                            "GLOBAL_BODY_SEARCH",
                                            IllegalStateException("fixture"),
                                        )
                                    } else {
                                        null
                                    },
                                onRetry = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // Preserve the rendered evidence even if a later semantics or accessibility assertion fails.
        composeRule.onNodeWithTag("body-stage").captureRoboImage("src/test/snapshots/global_search_body_$name.png")
        composeRule.onNodeWithText("Local group name").assertIsDisplayed()
        if (failed) composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }
}
