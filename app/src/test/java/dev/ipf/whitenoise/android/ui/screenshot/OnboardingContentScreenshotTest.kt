package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline for the real onboarding entry screen ([OnboardingContent]) in its
 * idle state, light theme only. The composable fills its parent, so the
 * Robolectric window is pinned to a fixed compact-phone frame via
 * `@Config(qualifiers=...)` — `onRoot()` captures the whole window, so the
 * device size (not a child Surface size) controls the committed PNG. mdpi keeps
 * 1dp == 1px so the frame is exactly 360x780 and stays small/deterministic. The
 * frame is tall enough to show the whole lockup — plain WN mark, "White Noise"
 * wordmark, the rotating slogan (captured at its first frame, "Decentralized",
 * since the test doesn't advance the clock), and the bottom slate with the
 * Sign In and Sign Up actions — in one shot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class OnboardingContentScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onboardingIdleLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
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
