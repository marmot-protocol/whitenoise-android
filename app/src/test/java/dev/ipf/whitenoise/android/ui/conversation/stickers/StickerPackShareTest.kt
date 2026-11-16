package dev.ipf.whitenoise.android.ui.conversation.stickers

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.state.StickerAssetCache
import dev.ipf.whitenoise.android.state.StickerLibrary
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sharing a pack waits for MDK's relay-naming link without opening a share sheet per tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class StickerPackShareTest {
    // Effects run unconfined so the share coroutine starts within the tap, as on the main thread.
    @get:Rule val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    /** A second tap while MDK builds the link opens no second sheet, and the one sheet carries MDK's link. */
    @Test
    fun repeatedTapsOpenOneSheetWithMdksLink() {
        val mdkLink = "https://sonarprivacy.xyz/stickers?a=30031%3Aauthor%3Acats&relay=wss%3A%2F%2Frelay.example"
        val pending = CompletableDeferred<String?>()
        var requests = 0
        val library =
            StickerLibrary(
                packs = { _, _ -> emptyList() },
                syncPacks = {},
                fetchPack = { error("unused") },
                installPack = { error("unused") },
                uninstallPack = { error("unused") },
                packShareLink = {
                    requests++
                    pending.await()
                },
            )
        val cache =
            StickerAssetCache<ImageBitmap>(
                scope = CoroutineScope(Dispatchers.Unconfined),
                load = { null },
                maxBytes = Long.MAX_VALUE,
                sizeOf = { 1 },
            )
        val started = mutableListOf<Intent>()
        composeRule.setContent {
            val host = LocalContext.current
            val recording =
                object : ContextWrapper(host) {
                    override fun startActivity(intent: Intent) {
                        started += intent
                    }
                }
            CompositionLocalProvider(LocalContext provides recording) {
                WhiteNoiseTheme {
                    // The page emits its top bar and grid as siblings for the sheet's column to stack.
                    Column {
                        StickerPackDetailPage(
                            pack(),
                            library,
                            cache,
                            "Alice",
                            onOpenAuthor = {},
                            onSend = {},
                            onFailure = { _, _, _ -> },
                            onBack = {},
                            onClose = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("sticker.pack.share").performClick()
        composeRule.onNodeWithTag("sticker.pack.share").performClick()
        pending.complete(mdkLink)
        composeRule.waitForIdle()

        assertEquals(1, requests)
        assertEquals(1, started.size)
        val shared = started.single().getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals(mdkLink, shared?.getStringExtra(Intent.EXTRA_TEXT))
    }

    /** One pack with a single sticker, so the page has something to show. */
    private fun pack() =
        StickerPackFfi(
            "30031:${"cd".repeat(32)}:cats",
            "cd".repeat(32),
            "cats",
            "ef".repeat(32),
            1uL,
            "Office cats",
            null,
            null,
            emptyList(),
            null,
            false,
        )
}
