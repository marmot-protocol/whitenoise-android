package dev.ipf.whitenoise.android.ui.conversation.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cheap check that decides whether a message may render an event card, and so whether a page is held for its
 * cards (#2238), matches the forms the reference extractor turns into cards and nothing broader.
 */
class NostrEventCardTriggerTest {
    private val note = noteBech32("a".repeat(64))

    /** A `nostr:` event URI and an HTTP(S) link with a NIP-19 event segment both render cards, so both match. */
    @Test
    fun formsThatRenderCardsMatch() {
        listOf(
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

    /** An escaped `nostr\:` colon stays literal in MDK, so it renders no card and does not match. */
    @Test
    fun escapedNostrUriDoesNotMatch() {
        assertFalse(mayNameNostrEventCard("nostr\\:$note"))
        assertFalse("an escape is not a scheme", mayNameNostrEventCard("https\\:njump.me $note"))
    }

    /** Prose, bare tokens and scheme-less links never render a card, so they hold nothing. */
    @Test
    fun lookalikesThatRenderNoCardDoNotMatch() {
        listOf(
            "see footnote1 below",
            "footnote1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0jqgw0hqv",
            "bare $note",
            "njump.me/$note",
            "www.njump.me/$note",
            "foonostr:$note",
            "nostr:${note.uppercase()}",
            "nostr:note1abc",
            "https://example.com and then $note",
            "https://example.com/xnote1qqqqqqqq",
        ).forEach { text -> assertFalse("must not match: $text", mayNameNostrEventCard(text)) }
    }
}
