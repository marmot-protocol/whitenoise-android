package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBodyMarkdownComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingConfirmedTokensAreParsedForTheDisplayedBody() {
        val raw = "# Release notes\n**Important** details"
        val parsed =
            document(
                MarkdownBlockFfi.Heading(1u, listOf(MarkdownInlineFfi.Text("Release notes"))),
                MarkdownBlockFfi.Paragraph(
                    listOf(
                        MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("Important"))),
                        MarkdownInlineFfi.Text(" details"),
                    ),
                ),
            )

        composeRule.setContent {
            val resolved =
                rememberMessageMarkdownDocumentForDisplayedBody(
                    messageIdHex = "confirmed-id",
                    bodyText = raw,
                    recordPlaintext = raw,
                    storedDocument = emptyDocument(),
                    overrideDocument = null,
                    deleted = false,
                    persistedFailure = false,
                    fallbackParsingEnabled = true,
                    parseMarkdown = { parsed },
                )
            WhiteNoiseTheme {
                MessageFullScreenBody(
                    body = raw,
                    markdownDocument = resolved,
                    mentionDisplayName = null,
                    isGroupMember = null,
                    onNostrProfileTap = null,
                    onCopyMarkdownLink = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Release notes").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Release notes").assertIsDisplayed()
        composeRule.onNodeWithText("Important details").assertIsDisplayed()
        composeRule.onNodeWithText(raw).assertDoesNotExist()
    }

    @Test
    fun nonMarkdownMessageKindsKeepTheSafePlainTextFallback() {
        val raw = "**literal system text**"
        var parseCalls = 0

        composeRule.setContent {
            val resolved =
                rememberMessageMarkdownDocumentForDisplayedBody(
                    messageIdHex = "system-id",
                    bodyText = raw,
                    recordPlaintext = raw,
                    storedDocument = emptyDocument(),
                    overrideDocument = null,
                    deleted = false,
                    persistedFailure = false,
                    fallbackParsingEnabled = false,
                    parseMarkdown = {
                        parseCalls += 1
                        document(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("literal system text"))))
                    },
                )
            WhiteNoiseTheme {
                MessageFullScreenBody(
                    body = raw,
                    markdownDocument = resolved,
                    mentionDisplayName = null,
                    isGroupMember = null,
                    onNostrProfileTap = null,
                    onCopyMarkdownLink = {},
                )
            }
        }

        composeRule.onNodeWithText(raw).assertIsDisplayed()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(0, parseCalls) }
    }

    private fun document(vararg blocks: MarkdownBlockFfi) =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = blocks.toList(),
            blankLinesBefore = byteArrayOf(),
        )

    private fun emptyDocument() = document()
}
