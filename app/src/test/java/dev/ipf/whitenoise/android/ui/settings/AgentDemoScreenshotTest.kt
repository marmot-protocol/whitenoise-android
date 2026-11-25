package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.ui.conversation.agent.AgentTranscriptHarness
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** The Developer Tools agent demo: the scripted turn drawn by the production rows, answered by taps. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class AgentDemoScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val originalTimeZone = TimeZone.getDefault()

    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun inboxShowsHumanAgentAndMixedConversations() {
        val appState = AgentTranscriptHarness().appState(agentName = "Unused")
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                AgentDemoInbox(appState, onBack = {}, parseMarkdown = ::paragraphs)
            }
        }
        waitForText("I can review the greeting this afternoon.")
        composeRule.onNodeWithText("Demo agent").assertExists()
        composeRule.onNodeWithText("Release planning").assertExists()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/agent_demo_inbox_dark.png")
        composeRule.onNodeWithText("Release planning").performClick()
        waitForText("Can we try a shorter greeting?")
    }

    @Test
    fun answeringTheQuestionLeadsToTheApprovalLight() {
        render(dark = false)
        waitForText("Send answer")
        composeRule.onNodeWithText("Send answer").assertIsNotEnabled()
        composeRule.onNodeWithText("Bye").performClick()
        composeRule.onNodeWithText("Send answer").assertIsEnabled().performClick()
        waitForText("Allow once")
        composeRule.onNodeWithText("Answered: Bye").assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/agent_demo_approval_light.png")
    }

    @Test
    fun stopClosesTheOpenQuestionDark() {
        render(dark = true)
        waitForText("Send answer")
        composeRule.onNodeWithText("Stop").performClick()
        waitForText("Stopped")
        composeRule.onNodeWithText("Question closed").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show agent activity").performClick()
        composeRule.onNodeWithText("Question closed").assertExists()
        composeRule.onNodeWithText("Send answer").assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/agent_demo_stopped_dark.png")
    }

    private fun render(dark: Boolean) {
        val appState = AgentTranscriptHarness().appState(agentName = "Unused")
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                AgentDemoScreen(appState = appState, onBack = {}, parseMarkdown = ::paragraphs)
            }
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
    }

    /** Enough Markdown for the demo's messages: one paragraph per block, code spans as text. */
    private fun paragraphs(text: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks =
                text.split("\n\n").filter(String::isNotBlank).map { block ->
                    MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(block.replace("`", ""))))
                },
        )

    private companion object {
        const val WAIT_MS = 30_000L
    }
}
