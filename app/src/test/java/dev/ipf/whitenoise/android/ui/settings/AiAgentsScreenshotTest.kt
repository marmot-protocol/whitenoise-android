package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Deterministic visual coverage for the AI Agents copy and setup flow. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1600dp-mdpi")
class AiAgentsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The screen baseline covers the explanatory and connector copy. */
    @Test
    fun agentsScreen() {
        render()

        composeRule.onRoot().captureRoboImage("src/test/snapshots/ai_agents_screen.png")
    }

    /** The setup-sheet baseline covers the selected agent's installation instructions. */
    @Test
    fun codexSetupSheet() {
        render()
        composeRule
            .onNodeWithTag("settings.list")
            .performScrollToNode(hasTestTag("ai_agents.connector.codex"))
        composeRule.onNodeWithTag("ai_agents.connector.codex").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onRoot().captureRoboImage("src/test/snapshots/ai_agents_codex_setup_sheet.png")
    }

    private fun render() {
        composeRule.setContent {
            WhiteNoiseTheme {
                AiAgentsContent(
                    npub = TEST_NPUB,
                    onBack = {},
                    onCopy = { _, _ -> },
                    onOpenDocs = { true },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    companion object {
        private val TEST_NPUB = "npub1" + "a".repeat(58)
    }
}
