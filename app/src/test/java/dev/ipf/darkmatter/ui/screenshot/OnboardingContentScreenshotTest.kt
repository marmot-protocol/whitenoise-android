package dev.ipf.darkmatter.ui.screenshot

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.darkmatter.ui.OnboardingContent
import dev.ipf.darkmatter.ui.theme.DarkMatterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline for the real onboarding entry screen ([OnboardingContent]) in its
 * idle state, light theme only. The composable fills its parent, so it is
 * pinned to a fixed phone-ish frame here to keep the rendered surface (and the
 * committed PNG) small and deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OnboardingContentScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onboardingIdleLight() {
        composeRule.setContent {
            DarkMatterTheme(darkTheme = false) {
                Surface(modifier = Modifier.size(width = 320.dp, height = 480.dp)) {
                    OnboardingContent(
                        identity = "",
                        creatingIdentity = false,
                        signingInBusy = false,
                        onIdentityChange = {},
                        onCreateIdentity = {},
                        onImportIdentity = {},
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/onboarding_content_idle_light.png")
    }
}
