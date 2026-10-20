package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.whitenoise.android.state.resolveNotificationPreviewText
import dev.ipf.whitenoise.android.ui.conversation.messages.focusedMessagePreviewText
import dev.ipf.whitenoise.android.ui.group.MEMBER_HEX
import dev.ipf.whitenoise.android.ui.group.MEMBER_NPROFILE
import dev.ipf.whitenoise.android.ui.group.MEMBER_NPUB
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profile labels agree across projections without acquiring semantic mention or notification authority. */
class ProfileReferencePresentationCoverageTest {
    private val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace)

    /** Both native profile node kinds retain the same name, style and canonical tap destination. */
    @Test
    fun equivalentParsedProfilesShareTheirLabelWithoutRosterAuthority() {
        for (inline in profiles()) {
            val entity = entity(inline)
            val rendered =
                markdownInlinesToAnnotatedString(
                    inlines = listOf(inline),
                    codeStyle = codeStyle,
                    linkStyle = SpanStyle(),
                    mentionDisplayName = { "Alice" },
                    isGroupMember = { error("Visual profile identity must not consult group membership") },
                )
            assertEquals("@Alice", rendered.text)
            assertEquals(TextDirection.Content, rendered.paragraphStyles.single().item.textDirection)
            val preview =
                markdownDocumentToPreviewAnnotatedString(document(inline), codeStyle, mentionDisplayName = { "Alice" })
            assertEquals(TextDirection.Content, preview.paragraphStyles.single().item.textDirection)
            assertEquals(FontWeight.Bold, rendered.spanStyles.single().item.fontWeight)
            assertEquals(
                NOSTR_PROFILE_LINK_TAG_PREFIX + entity.bech32,
                (rendered.getLinkAnnotations(0, rendered.length).single().item as LinkAnnotation.Clickable).tag,
            )
            assertEquals("@Alice", markdownDocumentToPreviewText(document(inline), mentionDisplayName = { "Alice" }))
            assertEquals("@Alice.", markdownDocumentToSpeakableText(document(inline), mentionDisplayName = { "Alice" }))
        }
    }

    /** Focused excerpts apply direction once across their authored separators and remain inert. */
    @Test
    fun focusedProfilePreviewHasOneDirectionRangeForTheCompleteExcerpt() {
        val first = document(profiles().last())
        val second = document(MarkdownInlineFfi.Text("second"))
        for (blocks in listOf(first.blocks, first.blocks + second.blocks)) {
            val preview = focusedMessagePreviewText("", first.copy(blocks = blocks), mentionDisplayName = { "Alice" })
            val direction = preview.paragraphStyles.single()
            assertEquals(TextDirection.Content, direction.item.textDirection)
            assertEquals(0, direction.start)
            assertEquals(preview.length, direction.end)
            assertEquals(if (blocks.size == 1) "@Alice" else "@Alice\n\nsecond", preview.text)
            assertTrue(preview.getLinkAnnotations(0, preview.length).isEmpty())
        }
    }

    /** A missing name remains a bounded clickable identity and can resolve without changing its target. */
    @Test
    fun unresolvedUriAndMentionKeepTheSameSafeFallback() {
        for (inline in profiles()) {
            val entity = entity(inline)
            val display =
                markdownNostrEntityDisplay(entity, inline is MarkdownInlineFfi.NostrMention, { null }, { false })
            assertEquals("@" + shortenedBech32(entity.bech32), display.visibleText)
            assertTrue(display.opensProfile)
            assertEquals(display.visibleText, markdownDocumentToPreviewText(document(inline)))
            assertEquals("@Alice", markdownNostrEntityDisplay(entity, false, { "Alice" }, { false }).visibleText)
        }
    }

    /** UTF-16 highlight coordinates still address the visible URI label beside supplementary characters. */
    @Test
    fun profileUriSpeechSpansMatchVisibleTextWithoutDirectionCharacters() {
        val uri = profiles().last()
        val parsed = document(MarkdownInlineFfi.Text("😀 "), uri, MarkdownInlineFfi.Text("!"))
        val projection = markdownDocumentToSpeakableProjection(parsed, mentionDisplayName = { "Alice" })
        val rendered =
            markdownInlinesToAnnotatedString(
                inlines = (parsed.blocks.single() as MarkdownBlockFfi.Paragraph).inlines,
                codeStyle = codeStyle,
                linkStyle = SpanStyle(),
                mentionDisplayName = { "Alice" },
            )
        assertEquals("😀 @Alice!", rendered.text)
        val span = projection.spans.single { it.leafId == "b0/n1" }
        assertEquals(SpeakableTextProjectionSpan(3, 9, "b0/n1", 0, 6), span)
        assertEquals("@Alice", projection.text.substring(span.spokenStart, span.spokenEnd))
        val link = rendered.getLinkAnnotations(0, rendered.length).single()
        assertEquals("@Alice", rendered.text.substring(link.start + span.visibleStart, link.start + span.visibleEnd))
        assertEquals(span.visibleEnd, link.end - link.start)
    }

    /** Display hydration accepts profile URIs without broadening the existing self-mention collector. */
    @Test
    fun profileHydrationAndSelfMentionDetectionStayDistinct() {
        val uri = MarkdownInlineFfi.NostrUri(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPROFILE, MEMBER_NPROFILE))
        assertEquals(setOf(MEMBER_NPROFILE), markdownDocumentProfileReferenceBech32s(document(uri)))
        assertEquals(emptySet<String>(), markdownDocumentMentionBech32s(document(uri)))
        assertFalse(documentMentionsAccount(document(uri), MEMBER_HEX) { MEMBER_HEX })
        val mention = MarkdownInlineFfi.NostrMention(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, MEMBER_NPUB))
        assertTrue(documentMentionsAccount(document(mention), MEMBER_HEX) { MEMBER_HEX })
    }

    /** Duplicate profile references resolve once per parsed representation in a notification projection. */
    @Test
    fun notificationPreviewHydratesUrisAndMentionsBeforeFlattening() {
        runTest {
            val references = profiles()
            val parsed = document(*(references + references).toTypedArray())
            val calls = mutableListOf<String>()
            val preview =
                resolveNotificationPreviewText(
                    raw = "authored identity text",
                    parseMarkdown = { parsed },
                    mentionDisplayName = { calls += it; "Alice" },
                )
            assertEquals(listOf(MEMBER_NPUB, MEMBER_NPROFILE), calls)
            assertEquals("@Alice".repeat(8), preview)
        }
    }

    /** Code, hashes, secrets and non-profile Nostr entities remain literal or inert and never hydrate. */
    @Test
    fun nonProfileAndLiteralInputsDoNotAcquireProfileIdentity() {
        val note = MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, "note1" + "q".repeat(58))
        val parsed =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Text("nsec1invalid " + "ab".repeat(32)),
                                MarkdownInlineFfi.Code(MEMBER_NPUB),
                                MarkdownInlineFfi.NostrUri(note),
                            ),
                        ),
                        MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "", MEMBER_NPROFILE),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(),
            )
        assertEquals(emptySet<String>(), markdownDocumentProfileReferenceBech32s(parsed))
        assertEquals(null, markdownNostrEntityDisplay(note, false, { error("Event is not a profile") }, null).name)
        assertFalse(markdownNostrEntityDisplay(note, false, null, null).opensProfile)
    }

    /** Presentation collection obeys the same sibling/depth budgets as the visible Markdown projection. */
    @Test
    fun collectionIsBoundedForPeerControlledTrees() {
        val uri = profiles().last()
        val broad = List(MARKDOWN_MAX_CONTAINER_SIBLINGS) { MarkdownInlineFfi.Text("") } + uri
        assertEquals(emptySet<String>(), markdownInlineProfileReferenceBech32s(broad))
        var deep: MarkdownInlineFfi = uri
        repeat(1000) { deep = MarkdownInlineFfi.Emph(listOf(deep)) }
        assertEquals(emptySet<String>(), markdownInlineProfileReferenceBech32s(listOf(deep)))
        assertEquals(setOf(MEMBER_NPROFILE), markdownInlineProfileReferenceBech32s(listOf(uri, uri)))
    }

    /** Typed disclosure previews hydrate profile labels while semantic mention collection is unchanged. */
    @Test
    fun disclosureProjectionIncludesItsProfileNames() {
        val uri = profiles().last()
        val parsed =
            MarkdownDocumentFfi(
                listOf(
                    MarkdownBlockFfi.Details(listOf(uri), false, listOf(document(uri).blocks.single()), byteArrayOf()),
                ),
                false,
                byteArrayOf(),
            )
        assertEquals(setOf(MEMBER_NPROFILE), markdownDocumentProfileReferenceBech32s(parsed))
        assertEquals(emptySet<String>(), markdownDocumentMentionBech32s(parsed))
        assertEquals("@Alice @Alice", markdownDocumentToPreviewText(parsed, mentionDisplayName = { "Alice" }))
    }

    /** Uses the existing published MDK identity vectors; AST construction is not native parsing evidence. */
    private fun profiles(): List<MarkdownInlineFfi> =
        listOf(
            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, MEMBER_NPUB),
            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPROFILE, MEMBER_NPROFILE),
        ).flatMap { listOf(MarkdownInlineFfi.NostrMention(it), MarkdownInlineFfi.NostrUri(it)) }

    /** Returns the canonical entity carried by a typed profile fixture. */
    private fun entity(inline: MarkdownInlineFfi): MarkdownNostrEntityFfi =
        when (inline) {
            is MarkdownInlineFfi.NostrMention -> inline.entity
            is MarkdownInlineFfi.NostrUri -> inline.entity
            else -> error("Expected native profile node")
        }

    /** Wraps fixture inlines without rewriting or parsing their authored identities. */
    private fun document(vararg inlines: MarkdownInlineFfi): MarkdownDocumentFfi =
        MarkdownDocumentFfi(listOf(MarkdownBlockFfi.Paragraph(inlines.toList())), false, byteArrayOf())
}
