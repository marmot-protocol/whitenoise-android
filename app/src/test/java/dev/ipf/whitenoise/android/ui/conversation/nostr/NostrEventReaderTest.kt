package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import dev.ipf.whitenoise.android.core.AvatarImageLoader
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

    @Test
    fun readerDiscoversBodyImagesWithoutAnInitialPublicImageFetch() {
        val downloads = java.util.concurrent.atomic.AtomicInteger()
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> downloads.incrementAndGet(); byteArrayOf() }
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    NostrEventReaderScreen(
                        card = noteCard().copy(imageUrls = emptyList()),
                        document = bodyImageDocument(),
                        parsing = false,
                        authorDisplayName = { "Alex" },
                        mentionDisplayName = { null },
                        onNostrProfileTap = {},
                        onDismiss = {},
                    )
                }
            }
            composeRule.onNodeWithTag(NOSTR_EVENT_READER_BODY_TAG).performScrollToNode(
                hasText(string(R.string.nostr_event_view_image)),
            )
            composeRule.onNodeWithText(string(R.string.nostr_event_view_image)).assertIsDisplayed()
            composeRule.runOnIdle { assertEquals(0, downloads.get()) }
        } finally {
            AvatarImageLoader.resetProfileImageFetcherForTests()
        }
    }

    @Test
    fun parserFailureKeepsTheCompleteBodyInTheReader() {
        val tail = "The complete fallback tail remains available."
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventReaderDialog(
                    card = noteCard().copy(readerBody = "Long fallback text ".repeat(600) + tail),
                    authoredReference = AUTHORED_REFERENCE,
                    authorDisplayName = { "Alex" },
                    mentionDisplayName = { null },
                    onNostrProfileTap = {},
                    parseMarkdown = { throw IllegalStateException("Parser unavailable") },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag(NOSTR_EVENT_READER_LOADING_TAG).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(NOSTR_EVENT_READER_BODY_TAG).performScrollToNode(hasText(tail, substring = true))
        composeRule.onNodeWithText(tail, substring = true).assertIsDisplayed()
    }

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
        composeRule.onNodeWithTag(NOSTR_EVENT_READER_BODY_TAG).performScrollToNode(hasText(LONG_MIDDLE))
        composeRule.onNodeWithText(LONG_MIDDLE).fetchSemanticsNode()
        composeRule.onNodeWithTag(NOSTR_EVENT_READER_BODY_TAG).performScrollToNode(
            hasText("The final paragraph is visible too."),
        )
        composeRule.onNodeWithText("The final paragraph is visible too.").assertIsDisplayed()
        composeRule.onNodeWithTag(NOSTR_EVENT_READER_BODY_TAG).performScrollToNode(hasText("Visit the project page"))
        val linkLayouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText("Visit the project page")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(linkLayouts) }
        val linkText = linkLayouts.single().layoutInput.text
        assertTrue(linkText.getLinkAnnotations(0, linkText.length).isNotEmpty())

        composeRule.onNodeWithTag(NOSTR_EVENT_READER_BODY_TAG).performScrollToNode(
            hasText("note1qqqqqqq", substring = true),
        )
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

    @Test
    fun largeBodyCanScrollToItsFinalParagraph() {
        val fullBody = "Long context ".repeat(10_000) + "\nLast event paragraph"
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventReaderScreen(
                    card = noteCard().copy(readerBody = fullBody),
                    document =
                        MarkdownDocumentFfi(
                            listOf(
                                paragraph(MarkdownInlineFfi.Text(fullBody.substringBefore("\nLast"))),
                                paragraph(MarkdownInlineFfi.Text("Last event paragraph")),
                            ),
                            false,
                            byteArrayOf(),
                        ),
                    parsing = false,
                    authorDisplayName = { "Alex" },
                    mentionDisplayName = { null },
                    onNostrProfileTap = {},
                    onDismiss = {},
                )
            }
        }
        composeRule
            .onNodeWithTag(NOSTR_EVENT_READER_BODY_TAG)
            .performScrollToNode(hasText("Last event paragraph", substring = true))
        composeRule.onNodeWithText("Last event paragraph").assertIsDisplayed()
    }

    @Test
    fun imageOnlyEventParsesAnEmptyBodyAndKeepsItsMediaControl() {
        renderEmptyReaderDialog(
            noteCard().copy(
                kind = NostrEventCardKind.Generic,
                eventKind = 20,
                readerBody = null,
                summary = null,
                imageUrls = listOf("https://images.example/manual-only"),
            ),
        )
        composeRule.onNodeWithText(string(R.string.nostr_event_view_image)).assertIsDisplayed()
    }

    @Test
    fun videoOnlyEventParsesAnEmptyBodyAndKeepsItsMediaControl() {
        renderEmptyReaderDialog(
            noteCard().copy(
                kind = NostrEventCardKind.Video,
                eventKind = 21,
                readerBody = null,
                summary = null,
                mediaUrl = "https://media.example/manual-only",
            ),
        )
        composeRule.onNodeWithText(string(R.string.nostr_event_play_video)).assertIsDisplayed()
    }

    @Test
    fun profileMentionsUseTheResolvedUsernameAndKeepTheirInAppLink() {
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventReaderScreen(
                    card = noteCard(),
                    document =
                        MarkdownDocumentFfi(
                            listOf(
                                paragraph(
                                    MarkdownInlineFfi.NostrMention(
                                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, "npub1example"),
                                    ),
                                ),
                            ),
                            false,
                            byteArrayOf(),
                        ),
                    parsing = false,
                    authorDisplayName = { "Alex" },
                    mentionDisplayName = { "Alice" },
                    onNostrProfileTap = {},
                    onDismiss = {},
                )
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText("Alice", substring = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        val text = layouts.single().layoutInput.text
        assertTrue(text.text.contains("Alice"))
        assertTrue(text.getLinkAnnotations(0, text.length).isNotEmpty())
    }

    private fun renderEmptyReaderDialog(card: NostrEventCardModel) {
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger()
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventReaderDialog(
                    card = card,
                    authoredReference = AUTHORED_REFERENCE,
                    authorDisplayName = { "Alex" },
                    mentionDisplayName = { null },
                    onNostrProfileTap = {},
                    parseMarkdown = { body ->
                        assertEquals("", body)
                        calls.incrementAndGet()
                        MarkdownDocumentFfi(emptyList(), false, byteArrayOf())
                    },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitUntil(10_000) {
            calls.get() == 1 &&
                composeRule
                    .onAllNodesWithTag(NOSTR_EVENT_READER_LOADING_TAG)
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
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

    private fun bodyImageDocument() =
        MarkdownDocumentFfi(
            blocks = listOf(
                paragraph(
                    MarkdownInlineFfi.Image(
                        dest = "https://images.example/reader-body",
                        title = null,
                        alt = listOf(MarkdownInlineFfi.Text("Photo caption")),
                        classification = MarkdownLinkDestinationKindFfi.WEB,
                    ),
                ),
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
