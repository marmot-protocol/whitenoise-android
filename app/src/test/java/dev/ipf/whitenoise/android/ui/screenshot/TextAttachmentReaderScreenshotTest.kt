package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.media.TEXT_ATTACHMENT_READER_RETRY_TAG
import dev.ipf.whitenoise.android.ui.conversation.media.TEXT_ATTACHMENT_READER_TAG
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentCandidate
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentFormat
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentPreview
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentReaderScreen
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentReaderState
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentUnavailableReason
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TextAttachmentReaderScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun mixedMarkdownLight() {
        render(
            candidate = markdownCandidate,
            state = TextAttachmentReaderState.Ready(markdownPreview()),
        )

        captureMarkdown("text_attachment_reader_markdown_light.png")
    }

    @Test
    fun mixedMarkdownDark() {
        render(
            candidate = markdownCandidate,
            state = TextAttachmentReaderState.Ready(markdownPreview()),
            darkTheme = true,
        )

        captureMarkdown("text_attachment_reader_markdown_dark.png")
    }

    @Test
    fun mixedMarkdownAmoled() {
        render(
            candidate = markdownCandidate,
            state = TextAttachmentReaderState.Ready(markdownPreview()),
            darkTheme = true,
            amoled = true,
        )

        captureMarkdown("text_attachment_reader_markdown_amoled.png")
    }

    @Test
    fun plainTextLargeFontRtl() {
        render(
            candidate = plainCandidate,
            state =
                TextAttachmentReaderState.Ready(
                    TextAttachmentPreview(
                        candidate = plainCandidate,
                        text =
                            "ملاحظات الاجتماع\n\n" +
                                "• تحقّق من سلامة الإصدار\n" +
                                "• شارك خطة الاختبار\n" +
                                "• احتفظ بالمصدر للقراءة فقط",
                    ),
                ),
            fontScale = 1.6f,
            layoutDirection = LayoutDirection.Rtl,
        )

        composeRule
            .onNodeWithTag(TEXT_ATTACHMENT_READER_TAG)
            .captureRoboImage("src/test/snapshots/text_attachment_reader_plain_large_rtl.png")
    }

    @Test
    fun readyActionsAreAccessibleAndDoNotOfferEditing() {
        var dismissed = 0
        var copied = ""
        var spoken = 0
        var opened = 0
        render(
            candidate = markdownCandidate,
            state = TextAttachmentReaderState.Ready(markdownPreview()),
            onDismiss = { dismissed += 1 },
            onCopy = { copied = it },
            onReadAloud = { spoken += 1 },
            onOpenExternal = { opened += 1 },
        )

        composeRule.onNodeWithContentDescription(string(R.string.back)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.copy_text)).assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription(string(R.string.speak_aloud)).assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription(string(R.string.text_attachment_open_external)).performClick()

        assertEquals(1, dismissed)
        assertEquals(markdownPreview().text, copied)
        assertEquals(1, spoken)
        assertEquals(1, opened)
        composeRule.onNodeWithText(string(R.string.save)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.edit)).assertDoesNotExist()
    }

    @Test
    fun downloadFailureOffersRetryAndExternalOpen() {
        var retried = 0
        var opened = 0
        render(
            candidate = plainCandidate,
            state = TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.DownloadFailed),
            onRetry = { retried += 1 },
            onOpenExternal = { opened += 1 },
        )

        composeRule.onNodeWithTag(TEXT_ATTACHMENT_READER_RETRY_TAG).performClick()
        composeRule.onNodeWithText(string(R.string.text_attachment_open_external)).performClick()

        assertEquals(1, retried)
        assertEquals(1, opened)
    }

    @Test
    fun truncatedMarkdownExplainsThePartialPreview() {
        render(
            candidate = markdownCandidate,
            state = TextAttachmentReaderState.Ready(markdownPreview(truncated = true)),
        )

        composeRule.onNodeWithText(string(R.string.text_attachment_preview_truncated)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.media_open)).assertIsEnabled()
    }

    @Test
    fun ordinaryMarkdownLongPressCopiesOnlyTheSelection() {
        val fullSource = "ordinary markdown selection words"
        var copied = ""
        render(
            candidate = markdownCandidate,
            state =
                TextAttachmentReaderState.Ready(
                    simpleMarkdownPreview(
                        source = fullSource,
                        inline = MarkdownInlineFfi.Text(fullSource),
                    ),
                ),
            onCopy = { copied = it },
        )

        composeRule.onNodeWithText(fullSource).performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription(string(R.string.copy_text)).performClick()

        assertTrue(copied.isNotBlank())
        assertTrue(fullSource.contains(copied))
        assertFalse(copied == fullSource)
    }

    @Test
    fun plainTextLongPressCopiesOnlyTheSelection() {
        val fullSource = "plain text selection words"
        var copied = ""
        render(
            candidate = plainCandidate,
            state =
                TextAttachmentReaderState.Ready(
                    TextAttachmentPreview(
                        candidate = plainCandidate,
                        text = fullSource,
                    ),
                ),
            onCopy = { copied = it },
        )

        composeRule.onNodeWithText(fullSource).performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription(string(R.string.copy_text)).performClick()

        assertTrue(copied.isNotBlank())
        assertTrue(fullSource.contains(copied))
        assertFalse(copied == fullSource)
    }

    @Test
    fun linkLongPressSelectsItsLabel() {
        val label = "your-bank.example"
        val destination = "https://example.com/attachment"
        val source = "[$label]($destination)"
        var copied = ""
        val preview =
            simpleMarkdownPreview(
                source = source,
                inline =
                    MarkdownInlineFfi.Link(
                        dest = destination,
                        title = null,
                        children = listOf(MarkdownInlineFfi.Text(label)),
                        classification = MarkdownLinkDestinationKindFfi.WEB,
                    ),
            )
        render(
            candidate = markdownCandidate,
            state = TextAttachmentReaderState.Ready(preview),
            onCopy = { copied = it },
        )

        composeRule.onNodeWithText(label).performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription(string(R.string.copy_text)).performClick()
        assertTrue(copied.isNotBlank())
        assertTrue(label.contains(copied))
        assertFalse(copied.contains("example.com/attachment"))
        assertFalse(copied == source)
    }

    @Test
    fun linkTapStillUsesTheExistingConfirmationFlow() {
        val label = "your-bank.example"
        val destination = "https://example.com/attachment"
        render(
            candidate = markdownCandidate,
            state =
                TextAttachmentReaderState.Ready(
                    simpleMarkdownPreview(
                        source = "[$label]($destination)",
                        inline =
                            MarkdownInlineFfi.Link(
                                dest = destination,
                                title = null,
                                children = listOf(MarkdownInlineFfi.Text(label)),
                                classification = MarkdownLinkDestinationKindFfi.WEB,
                            ),
                    ),
                ),
        )
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText(destination).assertIsDisplayed()
    }

    private fun render(
        candidate: TextAttachmentCandidate,
        state: TextAttachmentReaderState,
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        onDismiss: () -> Unit = {},
        onRetry: () -> Unit = {},
        onCopy: (String) -> Unit = {},
        onReadAloud: (TextAttachmentPreview) -> Unit = {},
        onOpenExternal: () -> Unit = {},
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        TextAttachmentReaderScreen(
                            candidate = candidate,
                            state = state,
                            onDismiss = onDismiss,
                            onRetry = onRetry,
                            onCopy = onCopy,
                            onReadAloud = onReadAloud,
                            onOpenExternal = onOpenExternal,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun string(resId: Int): String = context.getString(resId)

    private fun captureMarkdown(fileName: String) {
        composeRule
            .onNodeWithTag(TEXT_ATTACHMENT_READER_TAG)
            .captureRoboImage("src/test/snapshots/$fileName")
    }

    @Suppress("LongMethod") // One mixed fixture proves every renderer family in each theme baseline.
    private fun markdownPreview(truncated: Boolean = false) =
        TextAttachmentPreview(
            candidate = markdownCandidate,
            text =
                """
                # Release checklist

                Review the **safety notes** and confirm the [checksum](https://example.com/checksum).

                - Verify the build
                - Share the test plan

                ```kotlin
                val verified = true
                ```

                | Check | Status |
                | --- | --- |
                | Signature | Ready |
                """.trimIndent(),
            markdownDocument =
                MarkdownDocumentFfi(
                    truncated = truncated,
                    blocks =
                        listOf(
                            MarkdownBlockFfi.Heading(
                                level = 1u,
                                inlines = listOf(MarkdownInlineFfi.Text("Release checklist")),
                            ),
                            MarkdownBlockFfi.Paragraph(
                                inlines =
                                    listOf(
                                        MarkdownInlineFfi.Text("Review the "),
                                        MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("safety notes"))),
                                        MarkdownInlineFfi.Text(" and confirm the "),
                                        MarkdownInlineFfi.Link(
                                            dest = "https://example.com/checksum",
                                            title = null,
                                            children = listOf(MarkdownInlineFfi.Text("checksum")),
                                            classification = MarkdownLinkDestinationKindFfi.WEB,
                                        ),
                                        MarkdownInlineFfi.Text("."),
                                    ),
                            ),
                            MarkdownBlockFfi.ListBlock(
                                kind = MarkdownListKindFfi.Bullet("-"),
                                tight = true,
                                items =
                                    listOf(
                                        listItem("Verify the build"),
                                        listItem("Share the test plan"),
                                    ),
                            ),
                            MarkdownBlockFfi.CodeBlock(
                                kind = MarkdownCodeBlockKindFfi.FENCED,
                                info = "kotlin",
                                content = "val verified = true\n",
                            ),
                            MarkdownBlockFfi.Table(
                                alignments = listOf(MarkdownAlignmentFfi.NONE, MarkdownAlignmentFfi.NONE),
                                header = listOf(tableCell("Check"), tableCell("Status")),
                                rows = listOf(listOf(tableCell("Signature"), tableCell("Ready"))),
                            ),
                        ),
                    blankLinesBefore = ByteArray(0),
                ),
        )

    private fun simpleMarkdownPreview(
        source: String,
        inline: MarkdownInlineFfi,
    ) = TextAttachmentPreview(
        candidate = markdownCandidate,
        text = source,
        markdownDocument =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(inline))),
                blankLinesBefore = ByteArray(0),
            ),
    )

    private fun listItem(text: String) =
        MarkdownListItemFfi(
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))),
            checked = null,
            blankLinesBefore = ByteArray(0),
        )

    private fun tableCell(text: String) = MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text(text)))

    private companion object {
        val markdownCandidate =
            TextAttachmentCandidate("release-notes.md", "text/markdown", TextAttachmentFormat.Markdown)
        val plainCandidate =
            TextAttachmentCandidate("ملاحظات-الاجتماع.txt", "text/plain", TextAttachmentFormat.PlainText)
    }
}
