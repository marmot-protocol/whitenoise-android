package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h900dp-mdpi")
class ReportMessageSheetTerminalTest {
    @get:Rule val rule = createComposeRule()

    @Test fun successfulTeamRetryKeepsItsAudienceUntilTerminalDismissal() {
        val shown = mutableStateOf(true)
        val audience = mutableStateOf(ReportAudience.TeamMessage)
        var submitted: (() -> Unit)? = null
        var dismissals = 0
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                if (shown.value) {
                    ReportMessageSheet(
                        audience = audience.value,
                        onDismissRequest = {
                            dismissals++
                            shown.value = false
                        },
                        onSubmit = { _, _, complete -> submitted = complete },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(REPORT_TEAM_TOGGLE_TEST_TAG).assertDoesNotExist()
        rule.onNodeWithText(GROUP_DETAIL).assertDoesNotExist()
        rule.onNodeWithTag("message.report.send").performClick()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            assertNotNull(submitted)
            // The host clears the completed group result before accepting terminal close.
            audience.value = ReportAudience.GroupAndTeam
            submitted!!()
        }
        rule.mainClock.advanceTimeBy(80)
        rule.onNodeWithTag(REPORT_SHEET_TEST_TAG).assertExists()
        rule.onNodeWithTag(REPORT_TEAM_TOGGLE_TEST_TAG).assertDoesNotExist()
        rule.onNodeWithText(GROUP_DETAIL).assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, dismissals) }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { assertEquals(1, dismissals) }
        rule.onNodeWithTag(REPORT_SHEET_TEST_TAG).assertDoesNotExist()
    }

    private companion object {
        const val GROUP_DETAIL =
            "Your report goes to this group's admins, encrypted like any other message. They will see who reported it."
    }
}
