package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class NostrEventReaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Verifies the note reader keeps full safe content, event context, and independent controls. */
    @Test
    fun noteReaderShowsCompleteMarkdownAndContextWithoutRecursiveCards() {
        var copies = 0
        var externalOpens = 0
        var dismissals = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventReaderScreen(
                    card = noteCard(),
                    authoredReference = AUTHORED_REFERENCE,
                    document = readerDocument(),
                    parsing = false,
                    authorDisplayName = { "Alex Morgan" },
                    mentionDisplayName = { null },
                    onNostrProfileTap = {},
                    onCopyReference = { copies++ },
                    onOpenExternal = { externalOpens++ },
                    onDismiss = { dismissals++ },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.nostr_event_type_note)).assertIsDisplayed()
        composeRule.onNodeWithText("Alex Morgan ·", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(NOSTR_EVENT_READER_REFERENCE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("The complete first paragraph is visible.").assertIsDisplayed()
        composeRule.onNodeWithText(LONG_MIDDLE).fetchSemanticsNode()
        composeRule.onNodeWithText("The final paragraph is visible too.").fetchSemanticsNode()
        val linkLayouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText("Visit the project page")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(linkLayouts) }
        val linkText = linkLayouts.single().layoutInput.text
        assertTrue(linkText.getLinkAnnotations(0, linkText.length).isNotEmpty())

        val nestedReference = composeRule.onNodeWithText("note1qqqqqqq", substring = true).assertIsDisplayed()
        val nestedLayouts = mutableListOf<TextLayoutResult>()
        nestedReference.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(nestedLayouts) }
        val nestedText = nestedLayouts.single().layoutInput.text
        assertTrue(nestedText.getLinkAnnotations(0, nestedText.length).isEmpty())
        composeRule.onNodeWithTag(NOSTR_NOTE_PREVIEW_ACTION_TAG).assertDoesNotExist()

        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_copy)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_open)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.close)).performClick()

        assertEquals(1, copies)
        assertEquals(1, externalOpens)
        assertEquals(1, dismissals)
    }

    /** Builds the exact kind-1 event shown by the reader fixture. */
    private fun noteCard() =
        NostrEventCardModel(
            kind = NostrEventCardKind.Note,
            eventIdHex = "a".repeat(64),
            authorPubkeyHex = "b".repeat(64),
            createdAt = 1_765_000_000,
            eventKind = 1,
            title = null,
            summary = "The complete first paragraph is visible.",
            readerBody = "The complete reader body",
        )

    /** Builds a parsed body with ordinary text, an active web link, and an inert nested event reference. */
    private fun readerDocument() =
        MarkdownDocumentFfi(
            blocks =
                listOf(
                    paragraph(MarkdownInlineFfi.Text("The complete first paragraph is visible.")),
                    paragraph(
                        MarkdownInlineFfi.Text("Visit the "),
                        MarkdownInlineFfi.Link(
                            dest = "https://example.com/project",
                            title = null,
                            children = listOf(MarkdownInlineFfi.Text("project page")),
                            classification = MarkdownLinkDestinationKindFfi.WEB,
                        ),
                    ),
                    paragraph(
                        MarkdownInlineFfi.NostrUri(
                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, NESTED_NOTE),
                        ),
                    ),
                    paragraph(MarkdownInlineFfi.Text(LONG_MIDDLE)),
                    paragraph(MarkdownInlineFfi.Text("The final paragraph is visible too.")),
                ),
            truncated = false,
            blankLinesBefore = byteArrayOf(),
        )

    /** Builds one Markdown paragraph from the supplied inline nodes. */
    private fun paragraph(vararg inlines: MarkdownInlineFfi) = MarkdownBlockFfi.Paragraph(inlines.toList())

    /** Resolves one localized string through the Robolectric application context. */
    private fun string(resId: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private companion object {
        const val AUTHORED_REFERENCE = "nevent1qqs8f4r0originalreference6da8fv0"
        const val NESTED_NOTE = "note1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsn9e8p"
        val LONG_MIDDLE = "Long middle context ".repeat(80)
    }
}
