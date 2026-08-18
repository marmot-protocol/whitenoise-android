package dev.ipf.whitenoise.android.media

import android.graphics.BitmapFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GroupEmojiImageRendererTest {
    @Test
    fun oneEmojiProducesBoundedOpaqueSquareJpeg() {
        val draft = GroupEmojiImageRenderer.render(listOf("😀"), hasGlyph = { _, _ -> true })
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(draft.plaintext, 0, draft.plaintext.size))

        assertEquals("image/jpeg", draft.mediaType)
        assertEquals("${GROUP_EMOJI_IMAGE_SIZE_PX}x$GROUP_EMOJI_IMAGE_SIZE_PX", draft.dim)
        assertEquals(GROUP_EMOJI_IMAGE_SIZE_PX, bitmap.width)
        assertEquals(GROUP_EMOJI_IMAGE_SIZE_PX, bitmap.height)
        assertEquals(255, bitmap.getPixel(0, 0).ushr(24))
        assertTrue(draft.plaintext.size in 1..REMOTE_PROFILE_IMAGE_MAX_BYTES)
    }

    @Test
    fun completeCatalogSequencesReachTheGlyphCheckWithoutSplitting() {
        val firstPair = listOf("👩🏽‍💻", "👍🏿")
        val secondPair = listOf("🇳🇬", "1️⃣")
        val observed = mutableListOf<String>()

        GroupEmojiImageRenderer.render(firstPair) { _, emoji ->
            observed += emoji
            true
        }
        GroupEmojiImageRenderer.render(secondPair) { _, emoji ->
            observed += emoji
            true
        }

        assertEquals(firstPair + secondPair, observed)
    }

    @Test
    fun identicalSelectionHasDeterministicBytesAndOneVersusTwoUsesDifferentGeometry() {
        // Robolectric's bundled native font has no color-emoji outlines. Use
        // visible glyphs here to exercise the same one/two-slot geometry; the
        // separate sequence test pins complete emoji strings at the boundary.
        val first = GroupEmojiImageRenderer.render(listOf("A"), hasGlyph = { _, _ -> true })
        val retry = GroupEmojiImageRenderer.render(listOf("A"), hasGlyph = { _, _ -> true })
        val pair = GroupEmojiImageRenderer.render(listOf("A", "B"), hasGlyph = { _, _ -> true })

        assertArrayEquals(first.plaintext, retry.plaintext)
        assertNotEquals(first.mutationKey(), pair.mutationKey())
    }

    @Test
    fun unsupportedGlyphIsRejectedBeforeEncoding() {
        var checked = false
        val result =
            runCatching {
                GroupEmojiImageRenderer.render(listOf("🫨")) { _, _ ->
                    checked = true
                    false
                }
            }

        assertTrue(checked)
        assertTrue(result.exceptionOrNull() is GroupEmojiImageException.UnsupportedGlyph)
    }

    @Test
    fun selectionAllowsOneOrTwoAndKeepsTheExistingPairAtTheLimit() {
        val one = addGroupEmojiSelection(emptyList(), "😀")
        val two = addGroupEmojiSelection(one.emojis, "🚀")
        val rejectedThird = addGroupEmojiSelection(two.emojis, "🌙")

        assertEquals(listOf("😀"), one.emojis)
        assertFalse(one.limitReached)
        assertEquals(listOf("😀", "🚀"), two.emojis)
        assertFalse(two.limitReached)
        assertEquals(two.emojis, rejectedThird.emojis)
        assertTrue(rejectedThird.limitReached)
    }
}
