package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.nip19
import dev.ipf.whitenoise.android.core.nip19Tlv
import dev.ipf.whitenoise.android.core.nip19Uint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cheap check that decides whether a message may render an event card, and so whether a page is held for its
 * cards (#2238), matches the forms the reference extractor turns into cards and nothing broader.
 */
class NostrEventCardTriggerTest {
    private val note = noteBech32("a".repeat(64))

    /** The host admits each form the existing parser and event collector can turn into cards. */
    @Test
    fun formsThatRenderCardsMatch() {
        listOf(
            note,
            "see ($note).",
            nip19("nevent", nip19Tlv(0, List(32) { 17 })),
            nip19("naddr", nip19Tlv(0, listOf(97)) + nip19Tlv(2, List(32) { 17 }) + nip19Tlv(3, nip19Uint(30_617u))),
            "nostr:$note",
            "see (nostr:$note).",
            "https://njump.me/$note",
            "HTTPS://njump.me/$note",
            "[the post](https://example.com/e/$note?ref=1)",
            "<http://example.com/#$note>",
        ).forEach { text -> assertTrue("must match: $text", mayNameNostrEventCard(text)) }
    }

    /**
     * A link destination with Markdown backslash escapes renders a card, since MDK unescapes it before reading the
     * NIP-19 segment, so the check matches it as MDK reads it.
     */
    @Test
    fun escapedLinkDestinationsMatch() {
        listOf(
            "[post](https\\://njump.me/$note)",
            "[post](https:\\/\\/njump.me\\/$note)",
            "[post](<https\\://njump.me/$note>)",
            "[post](https://njump.me/e\\/$note)",
        ).forEach { text -> assertTrue("must match: $text", mayNameNostrEventCard(text)) }
    }

    /** Prose lookalikes and scheme-less links admit no event reference. */
    @Test
    fun lookalikesThatRenderNoCardDoNotMatch() {
        listOf(
            "see footnote1 below",
            "footnote1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0jqgw0hqv",
            "@$note",
            note.uppercase(),
            "note1" + "q".repeat(1020),
            "njump.me/$note",
            "www.njump.me/$note",
            "foonostr:$note",
            "nostr:${note.uppercase()}",
            "nostr:note1abc",
            "https://example.com/xnote1qqqqqqqq",
        ).forEach { text -> assertFalse("must not match: $text", mayNameNostrEventCard(text)) }
    }

    /**
     * Repeated schemes in one unbroken run are searched once, not once per scheme: a run of 16,384 `https://` before
     * an invalid segment rescanned its rest from every scheme, about a billion character steps, and stalled the page.
     */
    @Test(timeout = 5_000)
    fun repeatedSchemesBeforeAnInvalidSegmentStayLinear() {
        val schemes = "https://".repeat(16_384)
        assertFalse(mayNameNostrEventCard("${schemes}note1!"))
        assertFalse(mayNameNostrEventCard("[post](${schemes.replace("://", "\\://")}note1!)"))
        assertTrue(mayNameNostrEventCard("$schemes$note"))
        assertTrue(mayNameNostrEventCard("[post](${schemes.replace("://", "\\://")}$note)"))
    }

    /** Many short words, each its own run, are scanned in one forward pass rather than once per word. */
    @Test(timeout = 5_000)
    fun manyShortRunsStayLinear() {
        assertFalse(mayNameNostrEventCard("x ".repeat(50_000) + "note1!"))
        assertFalse(mayNameNostrEventCard("https:// ".repeat(50_000) + "note1!"))
        assertTrue(mayNameNostrEventCard("https:// ".repeat(50_000) + "https://njump.me/$note"))
        assertTrue(mayNameNostrEventCard("HTTPS://NJUMP.ME/${note.uppercase()}"))
    }

    /** A segment counts only after a scheme in its own run, however many unrelated schemes come first. */
    @Test
    fun segmentMatchesOnlyAfterSchemeInItsOwnRun() {
        assertTrue(mayNameNostrEventCard("http://a https://b http://njump.me/$note"))
        assertTrue(mayNameNostrEventCard("https://x\ty https://njump.me/$note"))
        assertFalse(mayNameNostrEventCard("https://a https://b\n${note.uppercase()}"))
        assertFalse(mayNameNostrEventCard("${note.uppercase()} https://njump.me/"))
    }
}
