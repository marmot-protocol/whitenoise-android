package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.AiAgentsContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Setup copy stays reviewable and the copy action remains reachable for the new connector. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AgentSetupGuidanceScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun claudeLight() = capture("claude", "ai_agents_claude_setup_light", dark = false)

    @Test
    fun claudeDark() = capture("claude", "ai_agents_claude_setup_dark", dark = true)

    @Test
    fun claudeLargeRtl() =
        capture("claude", "ai_agents_claude_setup_large_rtl", dark = false, largeRtl = true)

    @Test
    fun hermesProfileGuidance() = capture("hermes", "ai_agents_hermes_profile_setup_light", dark = false)

    private fun capture(
        connector: String,
        name: String,
        dark: Boolean,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (largeRtl) 2f else 1f) {
                    AiAgentsContent(npub = PREVIEW_NPUB, onBack = {}, onCopy = { _, _ -> }, onOpenDocs = { true })
                }
            }
        }
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("ai_agents.connector.$connector"))
        composeRule.onNodeWithTag("ai_agents.connector.$connector").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sheet.surface").captureRoboImage("src/test/snapshots/$name.png")
    }

    private companion object {
        private const val PREVIEW_NPUB = "npub1" + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
