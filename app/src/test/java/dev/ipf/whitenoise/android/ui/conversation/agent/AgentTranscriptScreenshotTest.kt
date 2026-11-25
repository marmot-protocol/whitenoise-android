package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.AgentTranscriptRows
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

/**
 * Real recorded agent sessions drawn by the production transcript row, beside the agent's own
 * message bubbles. Each scene loads a marmot-agents recording as the rows MDK projects and renders
 * every row through `TimelineRow`, with the conversation's turn folding.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class AgentTranscriptScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val originalTimeZone = TimeZone.getDefault()
    private val harness = AgentTranscriptHarness()

    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun approvalWaitsMidTurnAmoled() {
        val scene = scene("codex_approval_allowed", agentName = "Codex", events = 6, prompt = RUN_PROMPT)
        render(scene, amoled = true)
        composeRule.onNodeWithText("Allow once").assertIsEnabled()
        composeRule.onNodeWithText("Stop").assertIsEnabled()
        capture("agent_transcript_approval_amoled")
    }

    @Test
    fun finishedTurnCollapsesToQuietStepsAmoled() {
        val scene = scene("codex_approval_allowed", agentName = "Codex", prompt = RUN_PROMPT)
        render(scene, amoled = true)
        composeRule.onNodeWithText("Answered: Allow once").assertExists()
        composeRule.onNodeWithText("Ran a command").assertDoesNotExist()
        composeRule.onNodeWithText("1 step", substring = true).performClick()
        composeRule.onNodeWithText("Ran a command").performClick()
        capture("agent_transcript_finished_amoled")
    }

    @Test
    fun questionCardSendsOnlyWhenAnsweredLight() {
        val scene = scene("claude_question", agentName = "Claude", events = 3, prompt = "Which greeting should we use?")
        render(scene, amoled = false)
        composeRule.onNodeWithText("Send answer").assertIsNotEnabled()
        composeRule.onNodeWithText("Hello").performClick()
        composeRule.onNodeWithText("Send answer").assertIsEnabled()
        capture("agent_transcript_question_light")
    }

    @Test
    fun fileEditOpensToItsDiffDark() {
        val scene = scene("claude_file_edit", agentName = "Claude", prompt = "Make greet() end with a bang")
        render(scene, amoled = false, dark = true)
        composeRule.onNodeWithText("3 steps", substring = true).performClick()
        composeRule.onNodeWithText("Edited a file").performClick()
        capture("agent_transcript_file_edit_dark")
    }

    @Test
    fun failedCommandInGroupRtlLargeFont() {
        val scene =
            scene("claude_failing_command", agentName = "Claude", prompt = "Run the tests", group = true)
        render(scene, amoled = true, rtl = true, fontScale = LARGE_FONT)
        capture("agent_transcript_failed_group_rtl_large")
    }

    @Test
    fun planAndAnsweredQuestionLight() {
        val scene = scene("codex_plan_mode", agentName = "Codex", prompt = "Plan a farewell(name) function")
        render(scene, amoled = false)
        composeRule.onNodeWithText("3 steps", substring = true).performClick()
        composeRule.onNodeWithText("Plan").performClick()
        capture("agent_transcript_plan_light")
    }

    private class Scene(
        val rows: List<TimelineMessage>,
        val appState: WhiteNoiseAppState,
        val controller: ConversationController,
    )

    private fun scene(
        recording: String,
        agentName: String,
        events: Int = Int.MAX_VALUE,
        prompt: String? = null,
        group: Boolean = false,
    ): Scene {
        val rows = AgentRecordingFixtures.load(recording, events = events, prompt = prompt)
        val appState = harness.appState(agentName)
        val controller = harness.controller(appState, group)
        return Scene(rows, appState, controller)
    }

    private fun render(
        scene: Scene,
        amoled: Boolean,
        dark: Boolean = amoled,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Transcript(scene)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Transcript(scene: Scene) {
        Column(Modifier.width(360.dp).padding(vertical = 8.dp).testTag(TAG)) {
            AgentTranscriptRows(scene.rows, scene.appState, scene.controller)
        }
    }

    private fun capture(name: String) {
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private companion object {
        const val TAG = "agent-transcript"
        const val LARGE_FONT = 1.3f
        const val RUN_PROMPT = "Run greet.py and tell me what it prints"
    }
}
