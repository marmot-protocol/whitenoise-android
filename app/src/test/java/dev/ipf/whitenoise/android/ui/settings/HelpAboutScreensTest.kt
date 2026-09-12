package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract of Help, the bug report and About: every hand-off is reviewed first, states its own refusal, and
 * retries only the destination that failed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class HelpAboutScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val developerMode = mutableStateOf(false)
    private var unlocks = 0
    private var alreadyEnabled = 0
    private var developerOpens = 0
    private var bugReportOpens = 0
    private var licenseOpens = 0
    private var privacyOpens = 0
    private var reportAccepted = true
    private var licensesAccepted = true
    private var privacyAccepted = true

    /** Each Help row calls its caller once per tap. */
    @Test
    fun helpRowsOpenTheirDestinationsOncePerTap() {
        var bugReports = 0
        var abouts = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                HelpScreen(onBack = {}, onOpenBugReport = { bugReports++ }, onOpenAbout = { abouts++ })
            }
        }

        composeRule.onNodeWithTag("help.report_bug").performClick()
        composeRule.onNodeWithTag("help.about").performClick()

        composeRule.runOnIdle {
            assertEquals(1, bugReports)
            assertEquals(1, abouts)
        }
    }

    /** The bug report names its destination and every category it does not attach, before any hand-off. */
    @Test
    fun bugReportNamesTheDestinationAndWhatIsNotAttached() {
        renderBugReport()

        composeRule.onNodeWithText(app.getString(R.string.report_bug_destination)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.report_bug_destination_detail)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.report_bug_no_attachments_title)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.report_bug_no_attachments_detail)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.report_bug_public_reminder)).assertExists()
        composeRule.runOnIdle { assertEquals(0, bugReportOpens) }
    }

    /** An accepted hand-off leaves the screen as it was. */
    @Test
    fun anAcceptedBugReportHandOffShowsNoDialog() {
        renderBugReport()

        composeRule.onNodeWithTag("help.bug.open").performClick()

        composeRule.runOnIdle { assertEquals(1, bugReportOpens) }
        composeRule.onNodeWithTag("help.open_failed").assertDoesNotExist()
    }

    /** A refused hand-off explains why, Retry repeats it, and Cancel closes the dialog. */
    @Test
    fun aRefusedBugReportOffersRetryAndCancel() {
        reportAccepted = false
        renderBugReport()

        composeRule.onNodeWithTag("help.bug.open").performClick()
        composeRule.onNodeWithText(app.getString(R.string.report_bug_open_failed_title)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.report_bug_open_failed_detail)).assertExists()

        composeRule.onNodeWithText(app.getString(R.string.retry)).performClick()
        composeRule.runOnIdle { assertEquals(2, bugReportOpens) }

        composeRule.onNodeWithText(app.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithTag("help.open_failed").assertDoesNotExist()
    }

    /** About retains the installed build facts and the production version gesture. */
    @Test
    fun aboutShowsTheInstalledBuildFactsAndVersionAction() {
        renderAbout()

        composeRule.onNodeWithText(app.getString(R.string.about_version)).assertExists().assertHasClickAction()
        composeRule.onNodeWithText("1.2.3").assertExists()
        composeRule.onNodeWithText("42").assertExists()
        composeRule.onNodeWithText("abc1234").assertExists()
    }

    /** A refused licence hand-off names licences and retries only that destination. */
    @Test
    fun aRefusedLicenceHandOffRetriesOnlyLicences() {
        licensesAccepted = false
        renderAbout()

        composeRule.onNodeWithTag("about.licenses").performClick()
        composeRule.onNodeWithText(app.getString(R.string.licenses_open_failed_title)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.external_open_failed_detail)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.retry)).performClick()

        composeRule.runOnIdle {
            assertEquals(2, licenseOpens)
            assertEquals(0, privacyOpens)
        }
    }

    /** A refused privacy-policy hand-off names the policy and keeps the screen. */
    @Test
    fun aRefusedPrivacyHandOffNamesThePolicy() {
        privacyAccepted = false
        renderAbout()

        composeRule.onNodeWithTag("about.privacy_policy").performClick()

        composeRule.onNodeWithText(app.getString(R.string.privacy_policy_open_failed_title)).assertExists()
        composeRule.runOnIdle { assertEquals(1, privacyOpens) }
        composeRule.onNodeWithText(app.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithTag("help.open_failed").assertDoesNotExist()
    }

    /** Six taps do nothing; the seventh enables Developer once and reveals its destination. */
    @Test
    fun seventhVersionTapUnlocksDeveloperAndPreservesItsRoute() {
        renderAbout()
        repeat(6) { composeRule.onNodeWithTag("about.version").performClick() }
        composeRule.runOnIdle { assertEquals(0, unlocks) }
        composeRule.onNodeWithTag("about.developer").assertDoesNotExist()
        composeRule.onNodeWithTag("about.version").performClick()
        composeRule.onNodeWithTag("about.developer").performClick()
        composeRule.runOnIdle {
            assertEquals(1, unlocks)
            assertEquals(1, developerOpens)
        }
    }

    /** An already enabled installation gives feedback without toggling or writing the preference again. */
    @Test
    fun enabledDeveloperVersionTapOnlyShowsFeedback() {
        developerMode.value = true
        renderAbout()
        composeRule.onNodeWithTag("about.version").performClick()
        composeRule.runOnIdle {
            assertEquals(0, unlocks)
            assertEquals(1, alreadyEnabled)
        }
    }

    private fun renderBugReport() {
        composeRule.setContent {
            WhiteNoiseTheme {
                BugReportContent(
                    onBack = {},
                    onOpenReport = {
                        bugReportOpens++
                        reportAccepted
                    },
                )
            }
        }
    }

    private fun renderAbout() {
        composeRule.setContent {
            WhiteNoiseTheme {
                AboutContent(
                    developerMode = developerMode.value,
                    onEnableDeveloper = {
                        unlocks++
                        developerMode.value = true
                    },
                    onDeveloperAlreadyEnabled = { alreadyEnabled++ },
                    onOpenDeveloper = { developerOpens++ },
                    versionName = "1.2.3",
                    buildNumber = "42",
                    mdkShortSha = "abc1234",
                    onBack = {},
                    onOpenLicenses = {
                        licenseOpens++
                        licensesAccepted
                    },
                    onOpenPrivacy = {
                        privacyOpens++
                        privacyAccepted
                    },
                )
            }
        }
    }
}
