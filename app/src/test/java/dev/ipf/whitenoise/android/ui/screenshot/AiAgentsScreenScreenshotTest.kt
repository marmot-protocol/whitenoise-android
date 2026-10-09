package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.AiAgentsContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** AI Agents in both themes, without a public key, and with a connector's setup sheet open. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AiAgentsScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme. */
    @Test
    fun aiAgentsScreenLight() = capture("ai_agents_screen_light", dark = false, amoled = false)

    /** Dark theme. */
    @Test
    fun aiAgentsScreenDark() = capture("ai_agents_screen_dark", dark = true, amoled = false)

    /** AMOLED: outlined groups on black. */
    @Test
    fun aiAgentsScreenAmoled() = capture("ai_agents_screen_amoled", dark = true, amoled = true)

    /** Without a public key: the error callout above disabled connector rows. */
    @Test
    fun aiAgentsScreenWithoutPublicKey() = capture("ai_agents_screen_no_key_light", dark = false, npub = null)

    /** The Codex setup sheet: instruction, prompt surface and the pinned copy action. */
    @Test
    fun aiAgentsSetupSheetLight() = captureSheet("ai_agents_setup_sheet_light", dark = false, amoled = false)

    /** The same sheet in the dark theme. */
    @Test
    fun aiAgentsSetupSheetDark() = captureSheet("ai_agents_setup_sheet_dark", dark = true, amoled = false)

    /** Goose uses the same scrollable setup sheet in both themes. */
    @Test
    fun gooseSetupSheetLight() {
        captureSheet(
            "ai_agents_goose_setup_light",
            dark = false,
            amoled = false,
            connector = "goose",
        )
    }

    @Test
    fun gooseSetupSheetDark() {
        captureSheet(
            "ai_agents_goose_setup_dark",
            dark = true,
            amoled = false,
            connector = "goose",
        )
    }

    @Test
    @Config(qualifiers = "ar-w360dp-h780dp-mdpi")
    fun gooseSetupSheetRtl() {
        captureSheet(
            "ai_agents_goose_setup_rtl",
            dark = false,
            amoled = false,
            connector = "goose",
        )
    }

    @Test
    fun gooseSetupSheetLargeFont() =
        captureSheet(
            "ai_agents_goose_setup_large_font",
            dark = false,
            amoled = false,
            connector = "goose",
            fontScale = 1.6f,
        )

    /** Renders the fixture and records its screenshot baseline. */
    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean = false,
        npub: String? = PREVIEW_NPUB,
    ) {
        render(dark, amoled, npub)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Captures sheet. */
    private fun captureSheet(
        name: String,
        dark: Boolean,
        amoled: Boolean,
        connector: String = "codex",
        fontScale: Float = 1f,
    ) {
        render(dark, amoled, PREVIEW_NPUB, fontScale)
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("ai_agents.connector.$connector"))
        composeRule.onNodeWithTag("ai_agents.connector.$connector").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sheet.surface").captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Composes the surface under test with the given fixture. */
    private fun render(
        dark: Boolean,
        amoled: Boolean,
        npub: String?,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale) {
                AiAgentsContent(npub = npub, onBack = {}, onCopy = { _, _ -> }, onOpenDocs = { true })
            }
        }
    }

    private companion object {
        private const val PREVIEW_NPUB = "npub1" + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
