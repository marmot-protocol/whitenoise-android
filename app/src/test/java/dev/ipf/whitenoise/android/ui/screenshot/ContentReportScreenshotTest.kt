package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ContentReportFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.REPORT_EXPLANATION_LIMIT
import dev.ipf.whitenoise.android.state.REPORT_REASONS
import dev.ipf.whitenoise.android.state.reportReasonLabel
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_DETAILS_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageDetailsScreen
import dev.ipf.whitenoise.android.ui.conversation.messages.REPORT_BODY_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.ReportMessageForm
import dev.ipf.whitenoise.android.ui.conversation.messages.ReportMessageSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** A long form at compact height scrolls independently while its action remains visible and unchanged. */
    @Test
    fun reportFooterCompactLargeRtl() {
        val height = mutableStateOf(240.dp)
        val reason = mutableStateOf(ReportReasonFfi.SPAM)
        val explanation = mutableStateOf("Evidence " + "long explanation ".repeat(30))
        val sending = mutableStateOf(false)
        val submitted = mutableListOf<Pair<ReportReasonFfi, String>>()
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(darkTheme = true, fontScale = 2f) {
                    Surface(Modifier.fillMaxWidth().height(height.value).testTag("report.viewport")) {
                        ReportMessageForm(
                            reason = reason.value,
                            onReasonChange = { reason.value = it },
                            explanation = explanation.value,
                            onExplanationChange = { explanation.value = it },
                            sending = sending.value,
                            onSubmit = { value, text -> submitted += value to text },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("message.report.explanation").performScrollTo().assertIsDisplayed()
        assertPinnedFooter()
        composeRule
            .onNodeWithTag("report.viewport")
            .captureRoboImage("src/test/snapshots/message_report_compact_dark_large_rtl.png")
        composeRule.onNodeWithTag("message.report.send").performClick()
        assertEquals(listOf(ReportReasonFfi.SPAM to explanation.value.trim()), submitted)
        composeRule.runOnIdle { sending.value = true }
        composeRule.onNodeWithTag("message.report.send").assertIsNotEnabled()
        composeRule.runOnIdle { height.value = 480.dp }
        assertPinnedFooter()
        composeRule.runOnIdle { height.value = 200.dp }
        assertPinnedFooter()
        composeRule.runOnIdle {
            height.value = 100.dp
            sending.value = false
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        REPORT_REASONS.forEach { candidate ->
            composeRule
                .onNodeWithText(context.getString(reportReasonLabel(candidate)))
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            assertPinnedFooter()
        }
        composeRule.onNodeWithTag("message.report.explanation").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag("report.viewport")
            .captureRoboImage("src/test/snapshots/message_report_landscape_keyboard_large_rtl.png")
        composeRule.onNodeWithTag("message.report.send").performClick()
        assertEquals(REPORT_REASONS.last() to explanation.value.trim(), submitted.last())
    }

    /** Footer and scroll-body bounds are sampled from the production form after every viewport change. */
    private fun assertPinnedFooter() {
        composeRule.onNodeWithTag("message.report.send").assertIsDisplayed()
        val footer = composeRule.onNodeWithTag("message.report.send").fetchSemanticsNode().boundsInRoot
        val body = composeRule.onNodeWithTag(REPORT_BODY_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("report.viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("scrollable body collapsed", body.height > 0f)
        assertTrue("body overlaps footer", body.bottom <= footer.top)
        assertTrue("footer outside viewport", footer.bottom <= viewport.bottom)
    }

    /** Every reason stays reachable and the real field retains its bound after an oversized paste. */
    @Test
    fun everyReasonAndBoundedExplanationRemainUsable() {
        val submitted = mutableListOf<Pair<ReportReasonFfi, String>>()
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                ReportMessageSheet({}, { reason, explanation -> submitted += reason to explanation })
            }
        }
        REPORT_REASONS.forEach { reason ->
            composeRule.onNodeWithText(context.getString(reportReasonLabel(reason))).performScrollTo().performClick()
            composeRule.onNodeWithTag("message.report.send").assertIsDisplayed().performClick()
        }
        val oversized = "explanation ".repeat(200)
        composeRule.onNodeWithTag("message.report.explanation").performScrollTo().performTextReplacement(oversized)
        composeRule.onNodeWithTag("message.report.send").performClick()
        assertEquals(REPORT_REASONS, submitted.dropLast(1).map { it.first })
        assertEquals(oversized.take(REPORT_EXPLANATION_LIMIT).trim(), submitted.last().second)
    }

    /**
     * An admin sees complete reports in a consistently RTL Android window and composition. Matching the platform
     * direction avoids first measuring dialog text in LTR before applying the fixture's RTL composition local.
     * The synthetic English/Arabic-script locale preserves English text while Android derives RTL from its script;
     * Robolectric applies the locale after ldrtl, so plain English would overwrite the requested direction.
     */
    @Test
    @Config(qualifiers = "b+en+Arab-ldrtl-w360dp-h780dp-mdpi")
    fun detailsReportsDarkLargeRtl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("en", context.resources.configuration.locales[0].language)
        assertEquals("Arab", context.resources.configuration.locales[0].script)
        assertEquals(View.LAYOUT_DIRECTION_RTL, context.resources.configuration.layoutDirection)
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
