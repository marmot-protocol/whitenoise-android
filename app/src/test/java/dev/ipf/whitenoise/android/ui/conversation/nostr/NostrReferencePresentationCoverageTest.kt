package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.SpanStyle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.hexString
import dev.ipf.whitenoise.android.core.nip19
import dev.ipf.whitenoise.android.core.nip19Tlv
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.core.nostrEventReferences
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.markdownInlinesToAnnotatedString
import dev.ipf.whitenoise.android.ui.shortenedBech32
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Invariant gate for #3317: an event a card represents has one visible rich representation. Every reference that
 * only repeats it leaves the message prose, the card offers Copy reference with the complete value, and everything
 * else the author wrote stays: prose, meaningful link labels, web links, profile mentions and references past the
 * card cap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class NostrReferencePresentationCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Cancels the resolver scope each test opens. */
    @After
    fun closeScope() = scope.cancel()

    /** One represented event shows as its card alone, copies in full and leaves the unrelated prose intact. */
    @Test
    fun aRepresentedEventShowsOnceAndCopiesInFull() {
        val document =
            document(
                paragraph(text("Look at this "), uri(NOTE), text(".")),
                paragraph(uri(NEVENT, MarkdownNostrHrpFfi.NEVENT)),
                paragraph(
                    text("Read "),
                    MarkdownInlineFfi.Link(WEB_LINK, null, listOf(text("the docs")), LINK_WEB),
                    text(" with "),
                    MarkdownInlineFfi.NostrMention(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, NPUB)),
                ),
            )
        val references = nostrEventReferences(document)
        val resolver = loadedResolver()
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    MarkdownMessageBody(
                        document,
                        hiddenEventIds = references.mapTo(HashSet()) { it.reference.stableId },
                    )
                    NostrEventCards(
                        references = references,
                        resolver = resolver,
                        authorDisplayName = { "Alex" },
                        mentionDisplayName = { null },
                        onNostrProfileTap = {},
                        parseMarkdown = { content -> document(paragraph(text(content))) },
                        contentColor = Color.Black,
                    )
                }
            }
        }
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText(SUMMARY).fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(1, references.size)
        composeRule.onNodeWithText("Look at this.").assertIsDisplayed()
        composeRule.onNodeWithText("Read the docs with ", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText(SUMMARY).assertCountEquals(1)
        listOf("note1", "nevent1").forEach { hrp ->
            composeRule.onAllNodesWithText(hrp, substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_copy)).performClick()
        composeRule.waitForIdle()
        assertEquals("nostr:$NOTE", clipboardText())
        resolver.close()
    }

    /** Only the represented events leave the prose: a fourth reference past the card cap and a web link stay. */
    @Test
    fun referencesWithoutACardAndWebLinksStayInTheProse() {
        val notes = (1..4).map { fill -> nip19("note", List(32) { fill }) }
        val inlines =
            notes.flatMap { listOf(uri(it), text(" ")) } +
                MarkdownInlineFfi.Autolink(WEB_NEVENT, MarkdownAutolinkKindFfi.URI, LINK_WEB)
        val document = document(MarkdownBlockFfi.Paragraph(inlines))
        val represented = nostrEventReferences(document).mapTo(HashSet()) { it.reference.stableId }

        val rendered =
            markdownInlinesToAnnotatedString(
                inlines,
                codeStyle = SpanStyle(),
                linkStyle = SpanStyle(),
                hiddenEventIds = represented,
            ).text

        assertEquals(3, represented.size)
        notes.take(3).forEach { note -> assertFalse(rendered.contains(shortenedBech32(note))) }
        assertTrue(rendered.contains(shortenedBech32(notes.last())))
        assertTrue(rendered.contains(WEB_NEVENT))
    }

    /** A web link labelled with a carded reference keeps its label, so the link never vanishes from the prose. */
    @Test
    fun aWebLinkLabelledWithACardedReferenceKeepsItsLabel() {
        val link = MarkdownInlineFfi.Link(WEB_LINK, null, listOf(uri(NOTE)), LINK_WEB)
        val represented = nostrEventReferences(document(paragraph(link))).mapTo(HashSet()) { it.reference.stableId }

        val rendered =
            markdownInlinesToAnnotatedString(
                listOf(text("Read "), link),
                codeStyle = SpanStyle(),
                linkStyle = SpanStyle(),
                hiddenEventIds = represented,
            )

        assertEquals("the label's event gets a card", 1, represented.size)
        assertEquals("Read ${shortenedBech32(NOTE)}", rendered.text)
        assertTrue(rendered.getLinkAnnotations(0, rendered.length).isNotEmpty())
    }

    /** The bubble hides exactly the events its cards show, and no other surface hides references. */
    @Test
    fun onlyTheBubbleHidesReferencesAndOnlyThoseItsCardsShow() {
        val bubble = source("ui/conversation/messages/BubbleContentBlocks.kt")

        assertTrue(
            "the bubble must hide only the events its cards represent",
            Regex("""representedEventIds\s*=\s*remember\(eventReferences\)""").containsMatchIn(bubble),
        )
        assertTrue("the bubble must pass them to its body", "hiddenEventIds = representedEventIds" in bubble)
        assertTrue("only the references the cards render", "eventReferences.take(MAX_CARDS_PER_MESSAGE)" in bubble)
        assertTrue("the cards must render the same references", "references = eventReferences" in bubble)
        val hidingCallers =
            mainSources()
                .filter { it.name != "MarkdownRenderer.kt" && "hiddenEventIds = " in it.readText() }
                .map { it.name }
        assertEquals(listOf("BubbleContentBlocks.kt"), hidingCallers)
    }

    /** A real resolver that loads [NOTE]'s event from MDK's cached projection. */
    private fun loadedResolver() =
        NostrEventCardResolver(
            parentScope = scope,
            readCached = { references -> references.map { NostrEventCardState.Loaded(EVENT.toCardModel()) } },
            refresh = { NostrEventCardState.Loaded(EVENT.toCardModel()) },
        )

    /** The plain text on the system clipboard. */
    private fun clipboardText(): String =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSystemService(ClipboardManager::class.java)
            .primaryClip
            ?.getItemAt(0)
            ?.text
            .toString()

    /** A localized string. */
    private fun string(resId: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(resId)

    /** One main source file by its path below the app package. */
    private fun source(path: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing $path")

    /** Every Kotlin main source file. */
    private fun mainSources(): List<File> =
        listOf(File("src/main/java"), File("app/src/main/java"))
            .first(File::isDirectory)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** A parsed `nostr:` URI of [bech32] under [hrp]. */
    private fun uri(
        bech32: String,
        hrp: MarkdownNostrHrpFfi = MarkdownNostrHrpFfi.NOTE,
    ) = MarkdownInlineFfi.NostrUri(MarkdownNostrEntityFfi(hrp, bech32))

    /** Plain [content]. */
    private fun text(content: String) = MarkdownInlineFfi.Text(content)

    /** A paragraph of [inlines]. */
    private fun paragraph(vararg inlines: MarkdownInlineFfi) = MarkdownBlockFfi.Paragraph(inlines.toList())

    /** A document of [blocks] with no authored blank lines. */
    private fun document(vararg blocks: MarkdownBlockFfi): MarkdownDocumentFfi {
        val blankLines = ByteArray(blocks.size)
        return MarkdownDocumentFfi(blocks.toList(), truncated = false, blankLinesBefore = blankLines)
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val SUMMARY = "The represented note"
        const val WEB_LINK = "https://example.com/docs"
        val LINK_WEB = MarkdownLinkDestinationKindFfi.WEB
        val EVENT_ID = List(32) { 7 }
        val AUTHOR = List(32) { 6 }
        val NOTE = nip19("note", EVENT_ID)
        val NEVENT = nip19("nevent", nip19Tlv(0, EVENT_ID) + nip19Tlv(2, AUTHOR))
        val NPUB = nip19("npub", List(32) { 9 })
        val WEB_NEVENT = "https://njump.me/$NEVENT"
        val EVENT =
            NostrEvent(
                id = EVENT_ID.hexString(),
                pubkey = AUTHOR.hexString(),
                createdAt = 1_765_000_000,
                kind = 1,
                tags = emptyList(),
                content = SUMMARY,
                sig = "0".repeat(128),
            )
    }
}
