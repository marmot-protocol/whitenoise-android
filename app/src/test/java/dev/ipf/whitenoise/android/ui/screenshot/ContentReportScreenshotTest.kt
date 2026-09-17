package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ContentReportFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_DETAILS_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageDetailsScreen
import dev.ipf.whitenoise.android.ui.conversation.messages.ReportMessageSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** MarmotKit 0.10.1 content reports: the reporter's sheet and the reports an admin reviews in Message Details. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ContentReportScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var originalZone: TimeZone

    /** Keeps the filed-at timestamps deterministic across machines. */
    @Before fun setClockZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Restores process configuration for other screenshot fixtures. */
    @After fun restoreClockZone() {
        TimeZone.setDefault(originalZone)
    }

    /** The sheet lists every reason in the shared order, discloses who sees the report, and sends the chosen one. */
    @Test
    fun reportSheetLight() {
        val submitted = mutableListOf<Pair<ReportReasonFfi, String>>()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ReportMessageSheet(
                    onDismissRequest = {},
                    onSubmit = { reason, explanation -> submitted += reason to explanation },
                )
            }
        }
        composeRule.onNodeWithText("Report this message").assertIsDisplayed()
        composeRule.onNodeWithText("Impersonation").performClick()
        composeRule.onNodeWithTag("message.report").captureRoboImage(SHEET_BASELINE)
        composeRule.onNodeWithTag("message.report.send").performClick()
        assertEquals(listOf(ReportReasonFfi.IMPERSONATION to ""), submitted)
    }

    /** An admin sees each report's reason, explanation, reporter and time, with Dismiss only on open ones. */
    @Test
    fun detailsReportsDarkLargeRtl() {
        val dismissed = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(darkTheme = true, fontScale = 1.3f) {
                    MessageDetailsScreen(
                        record = record(),
                        status = MessageStatus.Received,
                        mine = false,
                        senderDisplayName = "Mallory",
                        senderNpub = "npub1mallory",
                        senderAvatarUrl = null,
                        reactions = emptyList(),
                        recipients = emptyList(),
                        attachmentLabels = emptyList(),
                        onDismissRequest = {},
                        onCopy = {},
                        reports =
                            listOf(
                                report("open", ReportReasonFfi.SPAM, "Posted the same link four times.", false),
                                report("closed", ReportReasonFfi.PROFANITY, "", dismissed = true),
                            ),
                        reporterName = { if (it == REPORTER) "Alice" else it },
                        canDismissReports = true,
                        onDismissReport = { dismissed += it.reportIdHex },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("message.details.report.1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(MESSAGE_DETAILS_TAG).captureRoboImage(DETAILS_BASELINE)
        composeRule.onNodeWithText("Dismiss report").performClick()
        assertEquals(listOf("open"), dismissed)
    }

    private fun record() =
        AppMessageRecordFfi(
            messageIdHex = "aa".repeat(32),
            direction = "received",
            groupIdHex = "bb".repeat(32),
            sender = "cc".repeat(32),
            plaintext = "Free coins, click here",
            contentTokens = EMPTY_DOCUMENT,
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1_800_000_000uL,
            receivedAt = 1_800_000_000uL,
        )

    private fun report(
        id: String,
        reason: ReportReasonFfi,
        explanation: String,
        dismissed: Boolean,
    ) = ContentReportFfi(
        reportIdHex = id,
        messageIdHex = "aa".repeat(32),
        messageAuthor = "cc".repeat(32),
        reporter = REPORTER,
        reason = reason,
        explanation = explanation,
        reportedAt = 1_800_000_300uL,
        dismissed = dismissed,
    )

    private companion object {
        val EMPTY_DOCUMENT =
            MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = byteArrayOf())
        const val REPORTER = "dd00000000000000000000000000000000000000000000000000000000000000"
        const val SHEET_BASELINE = "src/test/snapshots/message_report_sheet_light.png"
        const val DETAILS_BASELINE = "src/test/snapshots/message_details_reports_dark.png"
    }
}
