package dev.ipf.whitenoise.android.ui.conversation.stickers

import dev.ipf.marmotkit.StickerPackFfi
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
        assertEquals(true, loads(isFromMe = true, packInstalled = false, autoDownloadImages = false))
        assertEquals(true, loads(isFromMe = false, packInstalled = true, autoDownloadImages = true))
        assertEquals(false, loads(isFromMe = false, packInstalled = true, autoDownloadImages = false))
        assertEquals(false, loads(isFromMe = false, packInstalled = false, autoDownloadImages = true))
    }

    @Test
    fun aReceivedStickerWaitsForTheInstalledPacksBeforeOfferingATap() {
        assertNull(loads(isFromMe = false, packInstalled = null, autoDownloadImages = true))
        assertEquals(false, loads(isFromMe = false, packInstalled = null, autoDownloadImages = false))
        assertEquals(true, loads(isFromMe = true, packInstalled = null, autoDownloadImages = true))
    }

    private fun loads(
        isFromMe: Boolean,
        packInstalled: Boolean?,
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

    @Test
    fun shareLinkCarriesTheEncodedCoordinate() {
        val id = "office cats&more"
        val pack =
            StickerPackFfi(
                "30031:${"cd".repeat(32)}:$id",
                "cd".repeat(32),
                id,
                "ef".repeat(32),
                1uL,
                "Office cats",
                null,
                null,
                emptyList(),
                null,
                false,
            )
        assertEquals(
            "https://sonarprivacy.xyz/stickers?a=30031%3A${"cd".repeat(32)}%3Aoffice+cats%26more",
            stickerPackShareLink(pack),
        )
    }
}
