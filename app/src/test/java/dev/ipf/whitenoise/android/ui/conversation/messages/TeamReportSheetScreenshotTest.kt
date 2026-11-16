package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The report form when the White Noise team can receive it: a group report with its "Send to White Noise" switch, a
 * direct chat's team-only report, a profile report, and a failed team copy, across themes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h900dp-mdpi")
class TeamReportSheetScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** A group report names both audiences and sends the team a copy by default. */
    @Test
    fun groupAndTeamLight() {
        render(ReportAudience.GroupAndTeam, Theme.LIGHT)
        composeRule.onNodeWithText(GROUP_DETAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(REPORT_TEAM_TOGGLE_TEST_TAG).assertIsOn()
        composeRule.onNodeWithText(MESSAGE_TEAM_DETAIL).assertIsDisplayed()
        capture("group_and_team_light")
    }

    /** A direct chat reports only to the team, with no switch and no claim that admins see it. */
    @Test
    fun teamMessageDark() {
        render(ReportAudience.TeamMessage, Theme.DARK)
        composeRule.onNodeWithTag(REPORT_TEAM_TOGGLE_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(GROUP_DETAIL).assertDoesNotExist()
        composeRule.onNodeWithText(MESSAGE_TEAM_DETAIL).assertIsDisplayed()
        capture("team_message_dark")
    }

    /** A profile report is titled for the team and says no messages are shared. */
    @Test
    fun teamProfileAmoled() {
        render(ReportAudience.TeamProfile, Theme.AMOLED)
        composeRule.onNodeWithText("Report to White Noise team").assertIsDisplayed()
        composeRule.onNodeWithText(PROFILE_TEAM_DETAIL).assertIsDisplayed()
        capture("team_profile_amoled")
    }

    /** After the group took the report and the team copy failed, the form is locked and Send retries the copy. */
    @Test
    fun copyFailedLargeRtl() {
        render(
            ReportAudience.GroupAndTeam,
            Theme.LARGE_RTL,
            ReportFormState(locked = true, error = R.string.report_team_copy_failed),
        )
        composeRule.onNodeWithTag(REPORT_ERROR_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(REPORT_TEAM_TOGGLE_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag("message.report.send").assertIsEnabled()
        capture("copy_failed_large_rtl")
    }

    /** Turning the switch off removes the team's copy and its disclosure, and Send still reports to the group. */
    @Test
    fun turningTheCopyOffKeepsTheGroupReport() {
        var submitted: Pair<ReportReasonFfi, String>? = null
        var choice: ReportTeamChoice? = null
        render(ReportAudience.GroupAndTeam, Theme.LIGHT, onChoice = { choice = it }) { reason, text ->
            submitted = reason to text
        }

        composeRule.onNodeWithTag(REPORT_TEAM_TOGGLE_TEST_TAG).performClick().assertIsOff()
        composeRule.onNodeWithText(MESSAGE_TEAM_DETAIL).assertDoesNotExist()
        composeRule.onNodeWithTag("message.report.send").performClick()

        assertEquals(ReportReasonFfi.SPAM to "", submitted)
        assertEquals(false, choice?.reachesTeam)
        assertEquals(true, choice?.reachesGroup)
    }

    /** Only someone else's live message can be reported, and the team is offered only when this build reaches it. */
    @Test
    fun eachMessageReachesOnlyTheAudiencesThatCanReceiveIt() {
        assertEquals(ReportAudience.GroupAndTeam, messageReportAudience(false, false, false, teamAvailable = true))
        assertEquals(ReportAudience.Group, messageReportAudience(false, false, false, teamAvailable = false))
        assertEquals(ReportAudience.TeamMessage, messageReportAudience(false, false, true, teamAvailable = true))
        assertNull(messageReportAudience(false, false, true, teamAvailable = false))
        assertNull(messageReportAudience(true, false, false, teamAvailable = true))
        assertNull(messageReportAudience(false, true, false, teamAvailable = true))
    }

    /** Shows the report form for [audience] in [theme], with [form]'s sending state and a working team switch. */
    private fun render(
        audience: ReportAudience,
        theme: Theme,
        form: ReportFormState = ReportFormState(),
        onChoice: (ReportTeamChoice) -> Unit = {},
        onSubmit: (ReportReasonFfi, String) -> Unit = { _, _ -> },
    ) {
        val largeRtl = theme == Theme.LARGE_RTL
        composeRule.setContent {
            var reason by remember { mutableStateOf(ReportReasonFfi.SPAM) }
            var explanation by remember { mutableStateOf("") }
            var sendToTeam by remember { mutableStateOf(true) }
            val team = ReportTeamChoice(audience, sendToTeam) { sendToTeam = it }
            onChoice(team)
            WhiteNoiseTheme(
                darkTheme = theme != Theme.LIGHT,
                amoled = theme == Theme.AMOLED,
                fontScale = if (largeRtl) 2f else 1f,
            ) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.width(360.dp).wrapContentHeight().testTag(FORM_TAG)) {
                        ReportMessageForm(
                            reason,
                            { reason = it },
                            explanation,
                            { explanation = it },
                            form.sending,
                            onSubmit,
                            team,
                            form,
                        )
                    }
                }
            }
        }
    }

    /** Records the rendered form as `team_report_[name].png`. */
    private fun capture(name: String) {
        composeRule.onNodeWithTag(FORM_TAG).captureRoboImage("src/test/snapshots/team_report_$name.png")
    }

    private enum class Theme { LIGHT, DARK, AMOLED, LARGE_RTL }

    private companion object {
        const val FORM_TAG = "team-report-form"
        const val GROUP_DETAIL =
            "Your report goes to this group's admins, encrypted like any other message. They will see who reported it."
        const val MESSAGE_TEAM_DETAIL =
            "Your report and the sender’s public key are sent privately to the White Noise team. " +
                "The message itself isn’t shared, and the sender isn’t notified."
        const val PROFILE_TEAM_DETAIL =
            "Your report and this person’s public key are sent privately to the White Noise team. " +
                "No messages are shared, and this person isn’t notified."
    }
}
