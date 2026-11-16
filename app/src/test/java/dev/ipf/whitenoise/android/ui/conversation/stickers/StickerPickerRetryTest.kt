package dev.ipf.whitenoise.android.ui.conversation.stickers

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.StickerAssetCache
import dev.ipf.whitenoise.android.state.StickerLibrary
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sticker picker's Try again after its installed packs couldn't be read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StickerPickerRetryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Try again rereads the installed packs and replaces the failure with the pack. */
    @Test
    fun tryAgainRereadsTheInstalledPacks() {
        var readable = false
        var reads = 0
        val library =
            StickerLibrary(
                packs = { _, _ ->
                    reads++
                    if (readable) listOf(pack("Cats")) else error("pack storage unavailable")
                },
                syncPacks = { error("relay timeout") },
                fetchPack = { error("unused") },
                installPack = { error("unused") },
                uninstallPack = { error("unused") },
            )

        composeRule.setContent {
            WhiteNoiseTheme {
                StickerPickerPage(library, cache(), onBrowse = {}, onSend = {}, onClose = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sticker.load.failed").assertExists()
        assertEquals(1, reads)

        readable = true
        composeRule.onNodeWithText(app.getString(R.string.sticker_packs_try_again)).performClick()
        composeRule.waitForIdle()

        assertEquals(2, reads)
        assertTrue(library.hasLoadedInstalled)
        composeRule.onNodeWithTag("sticker.load.failed").assertDoesNotExist()
        composeRule.onNodeWithText("Cats").assertExists()
    }

    private companion object {
        val COORDINATE = "30031:${"cd".repeat(32)}:cats"

        fun cache() =
            StickerAssetCache<ImageBitmap>(
                scope = CoroutineScope(Dispatchers.Unconfined),
                load = { null },
                maxBytes = Long.MAX_VALUE,
                sizeOf = { 1 },
            )

        fun pack(title: String) =
            StickerPackFfi(
                COORDINATE,
                "cd".repeat(32),
                "cats",
                "ef".repeat(32),
                1uL,
                title,
                null,
                null,
                listOf(
                    StickerFfi(
                        COORDINATE,
                        "wave",
                        "https://example.invalid/wave.webp",
                        "ab".repeat(32),
                        "image/webp",
                        null,
                        null,
                        null,
                        null,
                    ),
                ),
                null,
                true,
            )
    }
}
