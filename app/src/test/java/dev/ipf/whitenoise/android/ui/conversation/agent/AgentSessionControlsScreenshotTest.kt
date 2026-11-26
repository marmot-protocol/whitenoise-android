package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemReader
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
class AgentSessionControlsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sessionControlsLight() = record(dark = false, direction = LayoutDirection.Ltr, fontScale = 1f, suffix = "light")

    @Test
    fun sessionControlsAmoledRtlLarge() {
        record(dark = true, direction = LayoutDirection.Rtl, fontScale = 1.3f, suffix = "amoled_rtl_large")
    }

    private fun record(
        dark: Boolean,
        direction: LayoutDirection,
        fontScale: Float,
        suffix: String,
    ) {
        val recording = AgentRecordingFixtures.load("codex_approval_allowed")
        val row = recording.first { it.projected?.agentItem?.itemType == "session" }
        val item = requireNotNull(AgentItemReader.read(row.record, row.projected))
        val session = item.body as AgentItemBody.Session
        val effort = session.settings.single { it.id == "effort" }
        val settingLabel = "${effort.label} · ${effort.value}"
        val newValue = "medium"
        val settings =
            session.settings.map { setting ->
                if (setting.id == effort.id) setting.copy(options = listOfNotNull(effort.value, newValue)) else setting
            }
        val requests = mutableListOf<AgentRequest>()
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = fontScale) {
                    Surface {
                        Column(Modifier.width(360.dp).padding(WhiteNoiseSpacing.Related).testTag(TAG)) {
                            AgentSessionContent(
                                item.copy(body = session.copy(settings = settings)),
                                AgentItemActions(
                                    agentName = "Codex",
                                    enabled = true,
                                    onSend = { requests += it },
                                    sessionControls = true,
                                ),
                            )
                        }
                    }
                }
            }
        }
        // Activate each disclosure without a pressed ripple in the snapshot.
        composeRule.onNodeWithText("Session controls").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/agent_session_controls_$suffix.png")
        composeRule.onNodeWithText(newValue).assertDoesNotExist()
        val disclosure = composeRule.onNodeWithText(settingLabel)
        val collapsed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")
        val expanded = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")
        disclosure.assert(collapsed).performSemanticsAction(SemanticsActions.OnClick).assert(expanded)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/agent_session_setting_choices_$suffix.png")
        composeRule.onNodeWithText(newValue).performClick()
        disclosure.assert(collapsed)
        composeRule.onNodeWithText(newValue).assertDoesNotExist()
        composeRule.onNodeWithText("New session").performClick()
        assertEquals(
            listOf(
                AgentRequest.Set(effort.id, newValue, "${effort.label} · $newValue"),
                AgentRequest.NewSession("New session"),
            ),
            requests,
        )
    }

    private companion object {
        const val TAG = "agent-session-controls"
    }
}
