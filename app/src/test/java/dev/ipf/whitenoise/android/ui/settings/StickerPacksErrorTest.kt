package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.marmotkit.MarmotKitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerPacksErrorTest {
    @Test
    fun importFailureNeverRendersNativeDetails() {
        val rendered =
            sanitizedStickerActionError(
                MarmotKitException.StickerImport("url contains pack_key=secret"),
                unsupportedImportError = "unsupported",
                genericStickerError = "try again",
            )

        assertEquals("try again", rendered)
        assertFalse(rendered.contains("pack_key"))
        assertFalse(rendered.contains("secret"))
    }

    @Test
    fun unsupportedSignerKeepsItsActionableMessage() {
        assertEquals(
            "unsupported",
            sanitizedStickerActionError(
                MarmotKitException.StickerImportUnsupported(),
                unsupportedImportError = "unsupported",
                genericStickerError = "try again",
            ),
        )
    }

    @Test
    fun reloadResultRequiresMatchingAccountAndSearch() {
        assertTrue(
            shouldApplyStickerPackReload(
                requestedAccount = "alice",
                requestedSearch = "cats",
                activeAccount = "alice",
                activeSearch = "cats",
            ),
        )
        assertFalse(
            shouldApplyStickerPackReload(
                requestedAccount = "alice",
                requestedSearch = "cats",
                activeAccount = "bob",
                activeSearch = "cats",
            ),
        )
        assertFalse(
            shouldApplyStickerPackReload(
                requestedAccount = "alice",
                requestedSearch = "cats",
                activeAccount = "alice",
                activeSearch = "dogs",
            ),
        )
    }
}
