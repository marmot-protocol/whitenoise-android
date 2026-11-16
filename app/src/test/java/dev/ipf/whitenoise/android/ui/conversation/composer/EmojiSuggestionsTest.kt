package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.whitenoise.android.ui.EmojiEntry
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Large built-in shortcode families leave broad Unicode name completion reachable. */
class EmojiSuggestionsTest {
    private val entries =
        listOf(
            EmojiEntry("🌙", "moon", 0, emptyList()),
            EmojiEntry("🐒", "monkey", 0, emptyList()),
            EmojiEntry("💰", "money bag", 0, emptyList()),
            EmojiEntry("👨", "man", 0, emptyList()),
            EmojiEntry("🦦", "otter", 0, emptyList()),
        ) + EmojiShortcodes.builtins.map { EmojiEntry(it, it.trim(':'), 0, listOf(it)) }

    /** Common prefixes reach Unicode results even with twelve Marmot codes in the registry. */
    @Test
    fun broadQueriesKeepUnicodeMatches() {
        assertTrue(emojiSuggestions(entries, emptyList(), "mo").containsAll(listOf("🌙", "🐒", "💰")))
        assertTrue("👨" in emojiSuggestions(entries, emptyList(), "ma"))
        assertTrue("🦦" in emojiSuggestions(entries, emptyList(), "ot"))
    }

    /** Exact family completion and a user's distinct alias keep their ordinary priority. */
    @Test
    fun explicitFamilyAndUserAliasesRemainAvailable() {
        assertEquals(12, emojiSuggestions(entries, emptyList(), "marmot").size)
        assertEquals(":moon_party:", emojiSuggestions(entries, listOf(":moon_party:"), "mo").first())
    }
}
