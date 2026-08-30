package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageFullScreenMarkdownTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun matchingDocumentKeepsRichStructure() {
        render(
            body = "# Release notes\n**Important** details",
            document =
                document(
                    MarkdownBlockFfi.Heading(1u, listOf(MarkdownInlineFfi.Text("Release notes"))),
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("Important"))),
                            MarkdownInlineFfi.Text(" details"),
                        ),
                    ),
                ),
        )

        composeRule.onNodeWithText("Release notes").assertIsDisplayed()
        composeRule.onNodeWithText("Important details").assertIsDisplayed()
        composeRule.onNodeWithText("# Release notes\n**Important** details").assertDoesNotExist()
    }

    @Test
    fun missingDocumentKeepsExactSafePlainTextFallback() {
        val raw = "# Not parsed\n**still literal**"

        render(body = raw, document = null)

        composeRule.onNodeWithText(raw).assertIsDisplayed()
    }

    /** A plain-text long press creates a non-empty native word selection. */
    @Test
    fun plainTextLongPressActivatesNativeSelectionForOnlyThePressedWord() {
        val fullText = "ordinary full screen selection words"
        var selection: ReaderTextSelectionController? = null
        render(
            body = fullText,
            document = null,
            onSelectionReady = { selection = it },
        )

        composeRule.onNodeWithText(fullText).performTouchInput { longClick() }
        composeRule.runOnIdle {
            val activeSelection = requireNotNull(selection)
            assertTrue(activeSelection.active)
            val selected = activeSelection.selectedText(fullText)
            assertTrue(selected.isNotBlank())
            assertTrue(fullText.contains(selected))
            assertFalse(selected == fullText)
        }
    }

    /** Selecting a Markdown label never activates its destination link. */
    @Test
    fun markdownLongPressSelectsRenderedTextWithoutActivatingTheLink() {
        val label = "ordinary label"
        val destination = "https://example.com/private"
        var copiedLink: String? = null
        var selection: ReaderTextSelectionController? = null
        render(
            body = "[$label]($destination)",
            document =
                document(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Link(
                                dest = destination,
                                title = null,
                                children = listOf(MarkdownInlineFfi.Text(label)),
                                classification = MarkdownLinkDestinationKindFfi.WEB,
                            ),
                        ),
                    ),
                ),
            onCopyLink = { copiedLink = it },
            onSelectionReady = { selection = it },
        )

        composeRule.onNodeWithText(label).performTouchInput { longClick() }
        composeRule.runOnIdle {
            val activeSelection = requireNotNull(selection)
            assertTrue(activeSelection.active)
            assertTrue(activeSelection.selectionState.selectedTexts.isNotEmpty())
            assertTrue(label.contains(activeSelection.selectedText(label)))
            assertEquals(null, copiedLink)
        }
    }

    @Test
    fun sharedLinkConfirmationAndAccessibilityCopyRemainAvailable() {
        val destination = "https://example.com/private/path"
        var copied: String? = null
        render(
            body = "your-bank.example",
            document =
                document(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Link(
                                dest = destination,
                                title = null,
                                children = listOf(MarkdownInlineFfi.Text("your-bank.example")),
                                classification = MarkdownLinkDestinationKindFfi.WEB,
                            ),
                        ),
                    ),
                ),
            onCopyLink = { copied = it },
        )

        val link = composeRule.onNodeWithText("your-bank.example")
        val actions = link.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(1, actions.size)
        actions.single().action()
        composeRule.runOnIdle { assertEquals(destination, copied) }

        link.performClick()
        composeRule.onNodeWithText(destination).assertIsDisplayed()
    }

    @Test
    fun nostrProfileUsesTheSameInAppRoutingCallback() {
        val npub = "npub1alice"
        var opened: String? = null
        render(
            body = npub,
            document =
                document(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.NostrMention(
                                MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub),
                            ),
                        ),
                    ),
                ),
            mentionDisplayName = { "Alice" },
            isGroupMember = { true },
            onNostrProfileTap = { opened = it },
        )

        composeRule.onNodeWithText("@Alice").performClick()
        composeRule.runOnIdle { assertEquals(npub, opened) }
    }

    @Test
    fun autolinkKeepsItsRenderedDestination() {
        val destination = "https://example.com/docs"
        render(
            body = destination,
            document =
                document(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Autolink(
                                destination,
                                MarkdownAutolinkKindFfi.URI,
                                MarkdownLinkDestinationKindFfi.WEB,
                            ),
                        ),
                    ),
                ),
        )

        composeRule.onNodeWithText(destination).assertIsDisplayed()
    }

    /** Renders the shared reader body while exposing its native selection owner. */
    private fun render(
        body: String,
        document: MarkdownDocumentFfi?,
        mentionDisplayName: ((String) -> String?)? = null,
        isGroupMember: ((String) -> Boolean)? = null,
        onNostrProfileTap: ((String) -> Unit)? = null,
        onCopyLink: (String) -> Unit = {},
        onSelectionReady: (ReaderTextSelectionController) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                val selection = rememberReaderTextSelectionController(body to document)
                onSelectionReady(selection)
                MessageFullScreenBody(
                    body = body,
                    markdownDocument = document,
                    mentionDisplayName = mentionDisplayName,
                    isGroupMember = isGroupMember,
                    onNostrProfileTap = onNostrProfileTap,
                    onCopyMarkdownLink = onCopyLink,
                    selectionController = selection,
                )
            }
        }
    }

    private fun document(vararg blocks: MarkdownBlockFfi) =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = blocks.toList(),
            blankLinesBefore = byteArrayOf(),
        )
}
