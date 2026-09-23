package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGiphyMediaTest {
    @Test
    fun parsesExactIosEnvelopeWithOptionalCreator() {
        val plain = RemoteGiphyMedia.parse("https://media.giphy.com/media/abc/giphy.gif\nvia GIPHY")
        val credited = RemoteGiphyMedia.parse("https://media12.giphy.com/media/abc/giphy.webp\nvia GIPHY · Alice")

        assertEquals("https://media.giphy.com/media/abc/giphy.gif", plain?.url)
        assertNull(plain?.attribution)
        assertEquals("Alice", credited?.attribution)
    }

    @Test
    fun acceptsOnlyPinnedHttpsHostsAndSupportedExtensions() {
        assertTrue(RemoteGiphyMedia.isAllowedMediaUrl("https://i.giphy.com/x.gif"))
        assertTrue(RemoteGiphyMedia.isAllowedMediaUrl("https://media0.giphy.com/x.mp4?cid=one"))
        assertFalse(RemoteGiphyMedia.isAllowedMediaUrl("http://media.giphy.com/x.gif"))
        assertFalse(RemoteGiphyMedia.isAllowedMediaUrl("https://giphy.com/x.gif"))
        assertFalse(RemoteGiphyMedia.isAllowedMediaUrl("https://media.giphy.com.evil.test/x.gif"))
        assertFalse(RemoteGiphyMedia.isAllowedMediaUrl("https://user@media.giphy.com/x.gif"))
        assertFalse(RemoteGiphyMedia.isAllowedMediaUrl("https://media.giphy.com:444/x.gif"))
        assertFalse(RemoteGiphyMedia.isAllowedMediaUrl("https://media.giphy.com/x.jpg"))
    }

    @Test
    fun rejectsMalformedOrEmbellishedEnvelopes() {
        val url = "https://media.giphy.com/media/abc/giphy.gif"
        assertNull(RemoteGiphyMedia.parse(" $url\nvia GIPHY"))
        assertNull(RemoteGiphyMedia.parse("$url\nvia giphy"))
        assertNull(RemoteGiphyMedia.parse("$url\nvia GIPHY · "))
        assertNull(RemoteGiphyMedia.parse("$url\nvia GIPHY · Alice  Smith"))
        assertNull(RemoteGiphyMedia.parse("$url\nvia GIPHY\nextra"))
        assertNull(RemoteGiphyMedia.parse("$url\nvia GIPHY · ${"a".repeat(81)}"))
        assertNull(RemoteGiphyMedia.parse("${"a".repeat(2049)}\nvia GIPHY"))
    }

    @Test
    fun previewDetectorRequiresPlausibleAttributionEvenWhenBodyIsClipped() {
        assertTrue(RemoteGiphyMedia.isEnvelopeText(" https://media.giphy.com/x \nvia GIP"))
        assertTrue(RemoteGiphyMedia.isEnvelopeText("https://media22.giphy.com/x\nvia GIPHY · Alice"))
        assertFalse(RemoteGiphyMedia.isEnvelopeText("https://media22.giphy.com/x\nunknown attribution"))
        assertFalse(RemoteGiphyMedia.isEnvelopeText("https://example.com/x.gif\nvia GIPHY"))
        assertFalse(RemoteGiphyMedia.isEnvelopeText("https://media.giphy.com/x"))
    }

    @Test
    fun legacyMp4RenditionMapsToTheSamePinnedGifPath() {
        val media = RemoteGiphyMedia.parse("https://media2.giphy.com/media/id/200.mp4?cid=x\nvia GIPHY")

        assertEquals("https://media2.giphy.com/media/id/200.gif?cid=x", media?.imageRequest()?.url)
        assertEquals("image/gif", media?.imageRequest()?.mediaType)
    }
}
