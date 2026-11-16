package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.conversation.stickers.StickerMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only a message that is exactly one custom emoji with ready artwork gives up its bubble. */
class LoneCustomEmojiPresentationTest {
    private val art = EmojiArt.Bundled(R.drawable.builtin_emoji_marmot)

    /** One resolvable shortcode stands alone, including when whitespace surrounds it. */
    @Test
    fun oneResolvableShortcodeStandsAlone() {
        assertEquals(":marmot:", LoneCustomEmojiPresentation.of(":marmot:", standsAlone = true) { art }?.shortcode)
        assertEquals(":wave:", LoneCustomEmojiPresentation.of("  :wave:\n", standsAlone = true) { art }?.shortcode)
    }

    /** Text, repeats, unresolved artwork and replies or media keep the bubble, resolving only lone codes. */
    @Test
    fun anythingElseKeepsTheBubble() {
        val asked = mutableListOf<String>()
        val resolve: (String) -> EmojiArt? = { code ->
            asked += code
            art.takeIf { code == ":marmot:" }
        }
        for (text in listOf(":marmot: hi", ":marmot::marmot:", ":marmot: :marmot:", "marmot", "`:marmot:`", "")) {
            assertNull(text, LoneCustomEmojiPresentation.of(text, standsAlone = true, resolve))
        }
        assertNull(LoneCustomEmojiPresentation.of(":party:", standsAlone = true, resolve))
        assertNull(LoneCustomEmojiPresentation.of(":marmot:", standsAlone = false, resolve))
        assertEquals(listOf(":party:"), asked)
    }

    /** A lone custom emoji takes the same space as a stand-alone sticker. */
    @Test
    fun loneEmojiUsesStickerSize() {
        assertEquals(StickerMetrics.MESSAGE_SIDE_DP, LoneCustomEmojiPresentation.SIDE_DP)
    }
}
