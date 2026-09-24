package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ReviewDemoProblem
import dev.ipf.whitenoise.android.state.ReviewDemoStage
import dev.ipf.whitenoise.android.state.ReviewDemoStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Stable Developer Tools frames for the new demo action and its recovery states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1600dp-mdpi")
class DeveloperReviewDemoScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun confirmation() {
        render(ReviewDemoStatus.Idle, hasSaved = false)
        composeRule.onNodeWithTag("developer.demo.action").performClick()
        settle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/developer_review_demo_confirmation.png")
    }

    @Test
    fun readyToOpen() {
        render(ReviewDemoStatus.Ready("original", "group"), hasSaved = true)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/developer_review_demo_ready.png")
    }

    @Test
    fun recoverableFailure() {
        render(
            ReviewDemoStatus.Failed(ReviewDemoStage.VerifyingDelivery, ReviewDemoProblem.DeliveryTimedOut),
            hasSaved = true,
        )
        composeRule.onRoot().captureRoboImage("src/test/snapshots/developer_review_demo_failure.png")
    }

    private fun render(
        status: ReviewDemoStatus,
        hasSaved: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                DeveloperContent(
                    developerMode = false,
                    streamingDebug = false,
                    build = DeveloperBuildFacts("1.4.0", "140", "0a5ab20"),
                    onDeveloperModeChange = {},
                    onStreamingDebugChange = {},
                    onBack = {},
                    onOpenDiagnostics = {},
                    onOpenKeyPackages = {},
                    demoStatus = status,
                    demoAvailable = true,
                    demoHasSavedSetup = hasSaved,
                )
            }
        }
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("developer.demo.action"))
        settle()
    }

    private fun settle() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }
}
