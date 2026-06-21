package dev.ipf.darkmatter.ui

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentMentionsAccountTest {
    private val aliceNpub = "npub1" + "q".repeat(58)
    private val bobNpub = "npub1" + "p".repeat(58)
    private val aliceHex = "aa".repeat(32)
    private val bobHex = "bb".repeat(32)

    // Fake FFI resolver: maps the two test npubs to their hex pubkeys.
    private val resolve: (String) -> String? = { bech32 ->
        when (bech32) {
            aliceNpub -> aliceHex
            bobNpub -> bobHex
            else -> null
        }
    }

    private fun mention(npub: String) = MarkdownInlineFfi.NostrMention(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub))

    private fun docOf(vararg inlines: MarkdownInlineFfi) = MarkdownDocumentFfi(listOf(MarkdownBlockFfi.Paragraph(inlines.toList())))

    @Test
    fun mentionOfSelfMatches() {
        val doc = docOf(MarkdownInlineFfi.Text("hey "), mention(aliceNpub))
        assertTrue(documentMentionsAccount(doc, aliceHex, resolve))
    }

    @Test
    fun mentionOfSomeoneElseDoesNotMatch() {
        val doc = docOf(MarkdownInlineFfi.Text("hey "), mention(bobNpub))
        assertFalse(documentMentionsAccount(doc, aliceHex, resolve))
    }

    @Test
    fun matchIsCaseInsensitiveOnHex() {
        val doc = docOf(mention(aliceNpub))
        // Account hex supplied in upper case still matches the resolver's lower.
        assertTrue(documentMentionsAccount(doc, aliceHex.uppercase(), resolve))
    }

    @Test
    fun noMentionsNeverMatches() {
        val doc = docOf(MarkdownInlineFfi.Text("plain message, no mentions"))
        assertFalse(documentMentionsAccount(doc, aliceHex, resolve))
    }

    @Test
    fun nullOrBlankAccountNeverMatches() {
        val doc = docOf(mention(aliceNpub))
        assertFalse(documentMentionsAccount(doc, null, resolve))
        assertFalse(documentMentionsAccount(doc, "  ", resolve))
    }

    @Test
    fun nestedMentionInsideEmphasisStillMatches() {
        val doc = docOf(MarkdownInlineFfi.Strong(listOf(mention(aliceNpub))))
        assertTrue(documentMentionsAccount(doc, aliceHex, resolve))
    }

    @Test
    fun mentionInBlockQuoteMatches() {
        val quote =
            MarkdownBlockFfi.BlockQuote(
                listOf(MarkdownBlockFfi.Paragraph(listOf(mention(aliceNpub)))),
            )
        val doc = MarkdownDocumentFfi(listOf(quote))
        assertTrue(documentMentionsAccount(doc, aliceHex, resolve))
    }

    @Test
    fun unresolvableMentionDoesNotMatch() {
        val unknownNpub = "npub1" + "z".repeat(58)
        val doc = docOf(mention(unknownNpub))
        assertFalse(documentMentionsAccount(doc, aliceHex, resolve))
    }
}
