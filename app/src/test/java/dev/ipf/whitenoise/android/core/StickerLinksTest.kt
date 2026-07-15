package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickerLinksTest {
    @Test
    fun classifiesCanonicalSonarPackInputs() {
        assertEquals(StickerInputKind.Pack, StickerLinks.classify("30031:author:cats")?.kind)
        assertEquals(StickerInputKind.Pack, StickerLinks.classify("naddr1example")?.kind)
        assertEquals(
            StickerInputKind.Pack,
            StickerLinks.classify("https://sonarprivacy.xyz/stickers?a=30031%3Aauthor%3Acats&relay=wss%3A%2F%2Fevil.test")?.kind,
        )
    }

    @Test
    fun classifiesSignalLinkWithoutExposingItsKey() {
        val link =
            "https://signal.art/addstickers/#pack_id=abcdefabcdef1234567890abcdef1234&" +
                "pack_key=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertEquals(StickerInput(value = link, kind = StickerInputKind.SignalImport), StickerLinks.classify(link))
    }

    @Test
    fun rejectsUntrustedOrIncompleteWebLinks() {
        assertNull(StickerLinks.classify("http://sonarprivacy.xyz/stickers?a=30031:author:cats"))
        assertNull(StickerLinks.classify("https://attacker@sonarprivacy.xyz/stickers?a=30031:author:cats"))
        assertNull(StickerLinks.classify("https://signal.art:8443/addstickers/#pack_id=abc&pack_key=def"))
        assertNull(StickerLinks.classify("https://evil.test/stickers?a=30031:author:cats"))
        assertNull(StickerLinks.classify("https://signal.art/addstickers/#pack_id=abc"))
        assertNull(StickerLinks.classify("https://sonarprivacy.xyz/stickers"))
    }
}
