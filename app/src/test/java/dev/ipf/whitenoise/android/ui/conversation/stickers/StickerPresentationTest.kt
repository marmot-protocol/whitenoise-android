package dev.ipf.whitenoise.android.ui.conversation.stickers

import dev.ipf.whitenoise.android.ui.conversation.stickers.StickerPresentation.DiscoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerPresentationTest {
    @Test
    fun aStickerReplacesTheBubbleOnlyWhenTheMessageCarriesNothingElse() {
        assertTrue(StickerPresentation.standsAlone(" \n", hasReply = false, hasMedia = false))
        assertFalse(StickerPresentation.standsAlone("hi", hasReply = false, hasMedia = false))
        assertFalse(StickerPresentation.standsAlone("", hasReply = true, hasMedia = false))
        assertFalse(StickerPresentation.standsAlone("", hasReply = false, hasMedia = true))
    }

    @Test
    fun receivedStickersLoadByThemselvesOnlyFromInstalledPacksWithAutoDownload() {
        assertTrue(loads(isFromMe = true, packInstalled = false, autoDownloadImages = false))
        assertTrue(loads(isFromMe = false, packInstalled = true, autoDownloadImages = true))
        assertFalse(loads(isFromMe = false, packInstalled = true, autoDownloadImages = false))
        assertFalse(loads(isFromMe = false, packInstalled = false, autoDownloadImages = true))
    }

    private fun loads(
        isFromMe: Boolean,
        packInstalled: Boolean,
        autoDownloadImages: Boolean,
    ) = StickerPresentation.loadsAutomatically(isFromMe, packInstalled, autoDownloadImages)

    @Test
    fun peerTextIsSanitizedAndBounded() {
        assertEquals("👋", StickerPresentation.labelEmoji(" 👋 "))
        assertNull(StickerPresentation.labelEmoji("wave"))
        assertNull(StickerPresentation.labelEmoji("👋👋👋👋👋"))
        assertNull(StickerPresentation.accessibleAlt("   "))
        assertEquals(120, StickerPresentation.accessibleAlt("a".repeat(500))?.length)
        assertEquals(119, StickerPresentation.accessibleAlt("a".repeat(119) + "😀")?.length)
        assertNull(StickerPresentation.lookupInput("  "))
        assertNull(StickerPresentation.lookupInput("n".repeat(2_049)))
        assertEquals("naddr1x", StickerPresentation.lookupInput(" naddr1x\n"))
    }

    @Test
    fun aFailedRefreshNeverReadsAsAnEmptyRelay() {
        fun state(
            loading: Boolean = false,
            count: Int = 0,
            search: String = "",
            failed: Boolean = false,
        ) = StickerPresentation.discoveryState(loading, count, search, failed)

        assertEquals(DiscoveryState.Packs, state(count = 2, failed = true))
        assertEquals(DiscoveryState.Loading, state(loading = true))
        assertEquals(DiscoveryState.Unreachable, state(failed = true, search = "cat"))
        assertEquals(DiscoveryState.NoMatches, state(search = "cat"))
        assertEquals(DiscoveryState.NoneOnRelays, state())
    }
}
