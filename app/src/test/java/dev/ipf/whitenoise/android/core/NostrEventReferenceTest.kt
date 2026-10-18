package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventReferenceTest {
    /** Note, nevent and naddr values decode through one strict path into their typed references. */
    @Test
    fun decodesNoteNeventAndNaddrThroughOneStrictPath() {
        val id = List(32) { it }
        val author = List(32) { 0xa0 + it }
        val note = nip19("note", id)
        val nevent =
            nip19(
                "nevent",
                nip19Tlv(1, "wss://ignored.example".utf8Ints()) +
                    nip19Tlv(0, id) +
                    nip19Tlv(2, author) +
                    nip19Tlv(3, nip19Uint(1u)) +
                    nip19Tlv(9, listOf(7, 8)),
            )
        val naddr =
            nip19(
                "naddr",
                nip19Tlv(0, "article-id".utf8Ints()) +
                    nip19Tlv(1, "wss://ignored.example".utf8Ints()) +
                    nip19Tlv(2, author) +
                    nip19Tlv(3, nip19Uint(30_023u)),
            )

        val expectedId = id.hexString()
        val expectedAuthor = author.hexString()
        assertEquals(NostrEventReference.Event(expectedId), NostrEventReferenceDecoder.eventReference(note))
        assertEquals(
            NostrEventReference.Event(
                expectedId,
                expectedAuthor,
                1u,
                relayHints = listOf("wss://ignored.example"),
            ),
            NostrEventReferenceDecoder.eventReference(nevent),
        )
        assertEquals(
            NostrEventReference.Address(
                30_023u,
                expectedAuthor,
                "article-id",
                relayHints = listOf("wss://ignored.example"),
            ),
            NostrEventReferenceDecoder.eventReference(naddr),
        )
    }

    /** Private, profile, oversized, malformed and conflicting pointers never decode as event references. */
    @Test
    fun rejectsPrivateProfileOversizeMalformedAndConflictingPointers() {
        val id = List(32) { 0x42 }
        val author = List(32) { 0x24 }
        val missingId = nip19("nevent", nip19Tlv(1, "wss://ignored.example".utf8Ints()))
        val duplicateId = nip19("nevent", nip19Tlv(0, id) + nip19Tlv(0, id))
        val duplicateAuthor = nip19("nevent", nip19Tlv(0, id) + nip19Tlv(2, author) + nip19Tlv(2, author))
        val missingCoordinate = nip19("naddr", nip19Tlv(0, "slug".utf8Ints()) + nip19Tlv(2, author))
        val controlCoordinate =
            nip19(
                "naddr",
                nip19Tlv(0, "bad\u0000slug".utf8Ints()) + nip19Tlv(2, author) + nip19Tlv(3, nip19Uint(1u)),
            )
        val unsupportedKind = nip19("nevent", nip19Tlv(0, id) + nip19Tlv(3, nip19Uint(UInt.MAX_VALUE)))
        val truncated = nip19("nevent", listOf(0, 32, 1, 2, 3))
        val validOversized =
            nip19(
                "nevent",
                nip19Tlv(0, id) + List(20) { index -> nip19Tlv(9, List(255) { index }) }.flatten(),
            )

        assertNull(NostrEventReferenceDecoder.eventReference(nip19("nsec", id)))
        assertNull(NostrEventReferenceDecoder.eventReference(nip19("npub", id)))
        assertNull(NostrEventReferenceDecoder.eventReference(missingId))
        assertNull(NostrEventReferenceDecoder.eventReference(duplicateId))
        assertNull(NostrEventReferenceDecoder.eventReference(duplicateAuthor))
        assertNull(NostrEventReferenceDecoder.eventReference(missingCoordinate))
        assertNull(NostrEventReferenceDecoder.eventReference(controlCoordinate))
        assertNull(NostrEventReferenceDecoder.eventReference(unsupportedKind))
        assertNull(NostrEventReferenceDecoder.eventReference(truncated))
        assertTrue(validOversized.length > 5_000)
        assertNull(NostrEventReferenceDecoder.eventReference(validOversized))
    }

    /** The document walk finds typed and HTTPS references once each, in order, and never inside code. */
    @Test
    fun documentWalkFindsTypedAndHttpsReferencesAndDeduplicatesThem() {
        val note = nip19("note", List(32) { 1 })
        val nevent = nip19("nevent", nip19Tlv(0, List(32) { 2 }))
        val fourth = nip19("note", List(32) { 4 })
        val naddr =
            nip19(
                "naddr",
                nip19Tlv(0, "entry".utf8Ints()) + nip19Tlv(2, List(32) { 3 }) + nip19Tlv(3, nip19Uint(30_023u)),
            )
        val document =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.NostrUri(
                                    MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, note),
                                ),
                                MarkdownInlineFfi.Link(
                                    dest = "https://example.com/p/$nevent?ref=$note",
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text("event")),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                                MarkdownInlineFfi.Autolink(
                                    url = "https://example.com/?pointer=$naddr",
                                    kind = MarkdownAutolinkKindFfi.URI,
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                                MarkdownInlineFfi.NostrMention(
                                    MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, fourth),
                                ),
                            ),
                        ),
                        MarkdownBlockFfi.CodeBlock(
                            kind = dev.ipf.marmotkit.MarkdownCodeBlockKindFfi.FENCED,
                            info = "",
                            content = nevent,
                        ),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(0, 0),
            )

        assertEquals(
            listOf(
                "event:${List(32) { 1 }.hexString()}",
                "event:${List(32) { 2 }.hexString()}",
                "address:30023:${List(32) { 3 }.hexString()}:entry",
            ),
            nostrEventReferences(document).map { it.reference.stableId },
        )
    }

    /** Typed disclosure summaries and bodies both retain their embedded event references. */
    @Test
    fun documentWalkFindsDetailsReferences() {
        val summary = nip19("note", List(32) { 1 })
        val body = nip19("note", List(32) { 2 })
        val summaryEntity = MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, summary)
        val bodyEntity = MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, body)
        val document =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Details(
                            summary = listOf(MarkdownInlineFfi.NostrUri(summaryEntity)),
                            open = false,
                            body =
                                listOf(
                                    MarkdownBlockFfi.Paragraph(
                                        listOf(MarkdownInlineFfi.NostrUri(bodyEntity)),
                                    ),
                                ),
                            blankLinesBefore = byteArrayOf(),
                        ),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(),
            )
        assertEquals(
            listOf("event:${List(32) { 1 }.hexString()}", "event:${List(32) { 2 }.hexString()}"),
            nostrEventReferences(document).map { it.reference.stableId },
        )
    }

    /** A reference inside a fenced code block stays literal and gets no card. */
    @Test
    fun documentWalkDoesNotScanCodeBlocks() {
        val nevent = nip19("nevent", nip19Tlv(0, List(32) { 5 }))
        val document =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.CodeBlock(
                            kind = dev.ipf.marmotkit.MarkdownCodeBlockKindFfi.FENCED,
                            info = "",
                            content = nevent,
                        ),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(0),
            )

        assertEquals(emptyList<NostrEventReferenceOccurrence>(), nostrEventReferences(document))
    }

    @Test
    fun standaloneEventReferencesCanMoveIntoCardsWithoutHidingProse() {
        val nevent = "nevent1qqspreviewreference"
        val references =
            listOf(
                NostrEventReferenceOccurrence(
                    reference = NostrEventReference.Event("a".repeat(64)),
                    authoredReference = nevent,
                ),
            )

        assertTrue(messageContainsOnlyNostrEventReferences(nevent, references))
        assertTrue(messageContainsOnlyNostrEventReferences("nostr:$nevent", references))
        assertTrue(messageContainsOnlyNostrEventReferences("NOSTR:$nevent", references))
        assertFalse(messageContainsOnlyNostrEventReferences("Read $nevent", references))
        assertFalse(messageContainsOnlyNostrEventReferences("[$nevent](https://example.com)", references))
    }

    /** Every alias and wrapper of an event a card represents counts as a repeat of it. */
    @Test
    fun aliasesAndWrappersOfARepresentedEventAreRepeats() {
        listOf(
            uri(NOTE),
            MarkdownInlineFfi.NostrMention(MarkdownNostrEntityFfi(NEVENT_HRP, NEVENT)),
            autolink("nostr:$NEVENT", MarkdownLinkDestinationKindFfi.NOSTR),
            nostrLink("nostr:$NOTE", MarkdownInlineFfi.Text("nostr:$NOTE")),
            nostrLink("nostr:$NEVENT", uri(NOTE)),
            nostrLink("nostr:$NOTE"),
            MarkdownInlineFfi.Strong(listOf(uri(NOTE))),
            MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text(" "), uri(NEVENT, NEVENT_HRP))),
        ).forEach { inline -> assertTrue("$inline repeats the card", inline.repeatsRepresentedEvent(REPRESENTED)) }
    }

    /** A meaningful label, a web link, a profile, another event, code and literal text are never repeats. */
    @Test
    fun unrelatedContentIsNeverARepeat() {
        val npub = nip19("npub", List(32) { 9 })
        listOf(
            nostrLink("nostr:$NOTE", MarkdownInlineFfi.Text("the whole thread")),
            autolink("https://njump.me/$NEVENT", MarkdownLinkDestinationKindFfi.WEB),
            MarkdownInlineFfi.Link(
                "https://njump.me/$NEVENT",
                null,
                listOf(MarkdownInlineFfi.Text(NEVENT)),
                MarkdownLinkDestinationKindFfi.WEB,
            ),
            MarkdownInlineFfi.NostrMention(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub)),
            uri(nip19("note", List(32) { 8 })),
            MarkdownInlineFfi.Code(NOTE),
            MarkdownInlineFfi.Text(NOTE),
            MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("Read "), uri(NOTE))),
        ).forEach { inline -> assertFalse("$inline is not a repeat", inline.repeatsRepresentedEvent(REPRESENTED)) }
        assertFalse(uri(NOTE).repeatsRepresentedEvent(emptySet()))
    }

    /**
     * Removing a repeat closes up the space it leaves, removes a bracket or quote pair it leaves empty, keeps words,
     * quotes and separators apart without doubling them, and drops a line it empties, at the end as well.
     */
    @Test
    fun removingRepeatsClosesUpTheProseAroundThem() {
        val text = MarkdownInlineFfi::Text
        val vectors =
            listOf(
                listOf(text("Look at "), uri(NOTE), text(" now")) to "Look at now",
                listOf(text("See "), uri(NOTE), text(".")) to "See.",
                listOf(uri(NOTE), MarkdownInlineFfi.SoftBreak, text("Hello")) to "Hello",
                listOf(text("a"), SOFT, uri(NOTE), MarkdownInlineFfi.HardBreak, text("b")) to "a\nb",
                listOf(text("x "), uri(NOTE), text(" "), uri(NEVENT, NEVENT_HRP), text(" y")) to "x y",
                listOf(bold("Read"), text(" "), uri(NOTE), text(" "), bold("this")) to "Read this",
                listOf(text("Keep "), nostrLink("nostr:$NOTE", text("this thread"))) to "Keep this thread",
                listOf(text("I wrote this ("), uri(NOTE), text(") last week")) to "I wrote this last week",
                listOf(text("("), uri(NOTE), text(") hello")) to "hello",
                listOf(text("Look: "), uri(NOTE), text(" — fine")) to "Look: — fine",
                listOf(text("Check this out:"), SOFT, uri(NOTE)) to "Check this out:",
                listOf(text("a"), SOFT, uri(NOTE), SOFT, uri(NEVENT, NEVENT_HRP)) to "a",
                listOf(text("He said \"hi\" "), uri(NOTE), text(" \"bye\"")) to "He said \"hi\" \"bye\"",
                listOf(text("the users' "), uri(NOTE), text(" 'best' post")) to "the users' 'best' post",
                listOf(text("say \""), uri(NOTE), text("\" now")) to "say now",
                listOf(text("options ( "), uri(NOTE), text(" ) here")) to "options here",
                listOf(text("Look at the"), uri(NOTE), text("")) to "Look at the",
                listOf(text("Hello, "), uri(NOTE), text(", goodbye")) to "Hello, goodbye",
                listOf(uri(NOTE), text(": details")) to "details",
                listOf(text("Wow! "), uri(NOTE), text(".")) to "Wow!",
            )

        vectors.forEach { (inlines, expected) ->
            assertEquals(expected, inlines.withoutRepeatsOf(REPRESENTED).plainText())
        }
        val untouched = listOf(text("No repeats "), uri(nip19("note", List(32) { 8 })))
        assertTrue(untouched.withoutRepeatsOf(REPRESENTED) === untouched)
    }

    /** Only a paragraph of repeats, whitespace and wrapping punctuation counts as saying nothing of its own. */
    @Test
    fun aParagraphOfOnlyRepeatsAndPunctuationSaysNothingOfItsOwn() {
        val text = MarkdownInlineFfi::Text

        assertTrue(listOf(text("("), uri(NOTE), text(")")).onlyRepeatsRepresentedEvents(REPRESENTED))
        assertTrue(listOf(uri(NOTE), SOFT, nostrLink("nostr:$NEVENT")).onlyRepeatsRepresentedEvents(REPRESENTED))
        assertFalse(listOf(text("Look: "), uri(NOTE)).onlyRepeatsRepresentedEvents(REPRESENTED))
        val reaction = listOf(uri(NOTE), text(" ???"))
        assertFalse("the author's own reaction stays", reaction.onlyRepeatsRepresentedEvents(REPRESENTED))
        assertFalse(listOf(uri(NOTE), MarkdownInlineFfi.Code("x")).onlyRepeatsRepresentedEvents(REPRESENTED))
        assertFalse(listOf(text(" ")).onlyRepeatsRepresentedEvents(REPRESENTED))
        assertFalse(listOf(uri(NOTE)).onlyRepeatsRepresentedEvents(emptySet()))
    }

    /** The cards stand alone only when every block is such a paragraph, never with headings, lists or extra cards. */
    @Test
    fun aDocumentOfOnlyRepeatsLetsTheCardsStandAlone() {
        val repeat = MarkdownBlockFfi.Paragraph(listOf(uri(NOTE)))
        val alias = MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(" "), uri(NEVENT, NEVENT_HRP)))
        val heading = MarkdownBlockFfi.Heading(1u, listOf(uri(NOTE)))

        assertTrue(document(repeat, alias).onlyRepeatsRepresentedEvents(REPRESENTED))
        assertFalse(document(repeat, heading).onlyRepeatsRepresentedEvents(REPRESENTED))
        assertFalse(document().onlyRepeatsRepresentedEvents(REPRESENTED))
    }

    /** A reference past the three-card cap has no card, so it stays in the prose and the body stays visible. */
    @Test
    fun referencesPastTheCardCapStayVisible() {
        val notes = (1..4).map { fill -> nip19("note", List(32) { fill }) }
        val inlines = notes.flatMap { listOf(uri(it), MarkdownInlineFfi.Text(" ")) }.dropLast(1)
        val document = document(MarkdownBlockFfi.Paragraph(inlines))
        val represented = nostrEventReferences(document).mapTo(HashSet()) { it.reference.stableId }

        assertEquals(3, represented.size)
        val kept = inlines.withoutRepeatsOf(represented).filterNot { it is MarkdownInlineFfi.Text }
        assertEquals(listOf(uri(notes.last())), kept)
        assertFalse(document.onlyRepeatsRepresentedEvents(represented))
    }

    /** A `nostr:` URI or bare value of one event decodes to the same stable id; a web address decodes to none. */
    @Test
    fun stableIdsIgnoreTheNostrSchemeButNeverDecodeWebAddresses() {
        val stableId = REPRESENTED.single()

        assertEquals(stableId, nostrEventStableId(NOTE))
        assertEquals(stableId, nostrEventStableId(" NOSTR:$NEVENT "))
        assertNull(nostrEventStableId("https://njump.me/$NEVENT"))
    }

    /** A parsed `nostr:` URI of [bech32] under [hrp]. */
    private fun uri(
        bech32: String,
        hrp: MarkdownNostrHrpFfi = MarkdownNostrHrpFfi.NOTE,
    ) = MarkdownInlineFfi.NostrUri(MarkdownNostrEntityFfi(hrp, bech32))

    /** A Markdown link to [dest] with [label] as its children. */
    private fun nostrLink(
        dest: String,
        vararg label: MarkdownInlineFfi,
    ) = MarkdownInlineFfi.Link(dest, null, label.toList(), MarkdownLinkDestinationKindFfi.NOSTR)

    /** An autolink to [url] classified as [kind]. */
    private fun autolink(
        url: String,
        kind: MarkdownLinkDestinationKindFfi,
    ) = MarkdownInlineFfi.Autolink(url, MarkdownAutolinkKindFfi.URI, kind)

    /** Bold [text]. */
    private fun bold(text: String) = MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text(text)))

    /** A document of [blocks] with no authored blank lines. */
    private fun document(vararg blocks: MarkdownBlockFfi): MarkdownDocumentFfi {
        val blankLines = ByteArray(blocks.size)
        return MarkdownDocumentFfi(blocks.toList(), truncated = false, blankLinesBefore = blankLines)
    }

    /** The words a reader sees, with breaks as newlines and emphasis flattened. */
    private fun List<MarkdownInlineFfi>.plainText(): String =
        joinToString("") { inline ->
            when (inline) {
                is MarkdownInlineFfi.Text -> inline.content
                is MarkdownInlineFfi.Strong -> inline.children.plainText()
                is MarkdownInlineFfi.Link -> inline.children.plainText()
                MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> "\n"
                else -> "<$inline>"
            }
        }

    private companion object {
        val EVENT_ID = List(32) { 7 }
        val NOTE = nip19("note", EVENT_ID)
        val NEVENT = nip19("nevent", nip19Tlv(0, EVENT_ID) + nip19Tlv(2, List(32) { 6 }))
        val REPRESENTED = setOf("event:${EVENT_ID.hexString()}")
        val NEVENT_HRP = MarkdownNostrHrpFfi.NEVENT
        val SOFT = MarkdownInlineFfi.SoftBreak
    }
}
