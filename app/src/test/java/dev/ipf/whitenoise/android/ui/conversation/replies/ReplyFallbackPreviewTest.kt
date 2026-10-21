package dev.ipf.whitenoise.android.ui.conversation.replies

import dev.ipf.whitenoise.android.ui.MARKDOWN_PREVIEW_MAX_LENGTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** First-frame previews obey the same text budget as their eventual Markdown projection. */
class ReplyFallbackPreviewTest {
    /** A large plain body is bounded without depending on asynchronous parsing. */
    @Test
    fun longBodyIsBounded() {
        assertEquals("W".repeat(MARKDOWN_PREVIEW_MAX_LENGTH), replyFallbackPreviewText("W".repeat(70_000), null))
    }

    /** Preview clipping never emits half of an emoji surrogate pair. */
    @Test
    fun clippingPreservesUnicodeBoundary() {
        val prefix = "a".repeat(MARKDOWN_PREVIEW_MAX_LENGTH - 1)
        val clipped = replyFallbackPreviewText(prefix + "\uD83D\uDE00 tail", null)
        assertEquals(prefix, clipped)
        assertFalse(Character.isHighSurrogate(clipped.last()))
    }

    /** Profile resolution cannot expand a short mention beyond the preview budget. */
    @Test
    fun expandedMentionIsBoundedAfterResolution() {
        val mention = "nostr:npub1" + "a".repeat(58)
        val result = replyFallbackPreviewText(mention) { "N".repeat(70_000) }
        assertEquals("@" + "N".repeat(MARKDOWN_PREVIEW_MAX_LENGTH - 1), result)
    }

    /** Short text retains existing sanitization and ordinary visible content. */
    @Test
    fun ordinaryTextAndEmptyInputStayUnchanged() {
        assertEquals("A short quote", replyFallbackPreviewText("A short quote", null))
        assertEquals("", replyFallbackPreviewText("", null))
    }
}
