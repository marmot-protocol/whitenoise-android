package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.StickerFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class StickersTest {
    @Test
    fun stickerReferenceBuildsTheExactSonarMessageTag() {
        val coordinate = "30031:${"ab".repeat(32)}:cats"
        val hash = "CD".repeat(32)
        val reference =
            StickerFfi(
                packCoordinate = coordinate,
                shortcode = "wave",
                url = "https://cdn.example/$hash.png",
                sha256 = hash,
                mime = "image/png",
                width = 512u,
                height = 512u,
                alt = "Waving cat",
                emoji = "👋",
            ).reference()

        assertEquals(listOf("sticker", coordinate, "wave", hash), reference.messageTag())
        assertEquals("$coordinate:wave:${hash.lowercase()}", reference.cacheIdentity())
    }
}
