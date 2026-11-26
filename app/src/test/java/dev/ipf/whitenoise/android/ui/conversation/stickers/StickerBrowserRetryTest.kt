package dev.ipf.whitenoise.android.ui.conversation.stickers

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.StickerLibrary
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A failed local discovery read retries the read itself, never waiting on a relay sync first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StickerBrowserRetryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** With every later relay sync hanging, Try again after a failed read still shows the packs at once. */
    @Test
    fun loadFailedRetryRereadsTheStoreWhileRelaysHang() {
        var readFails = true
        var syncs = 0
        val library =
            StickerLibrary(
                packs = { installedOnly, _ ->
                    check(installedOnly || !readFails) { "store read failed" }
                    if (installedOnly) emptyList() else listOf(pack())
                },
                syncPacks = {
                    // The first sync completes; a forced relay refresh never answers.
                    if (syncs++ > 0) awaitCancellation()
                },
                fetchPack = { error("offline") },
                installPack = { error("unused") },
                uninstallPack = { error("unused") },
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                // The page emits its top bar and list as siblings for the sheet's column to stack.
                Column {
                    StickerBrowserPage(library, { _, _, _ -> }, onOpen = {}, onBack = {}, onClose = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app.getString(R.string.sticker_packs_load_failed)).assertExists()

        readFails = false
        composeRule.onNodeWithText(app.getString(R.string.sticker_packs_try_again)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(PACK_TITLE).assertExists()
    }

    private companion object {
        const val PACK_TITLE = "Waving marmots"
        val COORDINATE = "30031:${"cd".repeat(32)}:wave"

        /** A discoverable pack with one sticker, not installed. */
        fun pack() =
            StickerPackFfi(
                COORDINATE,
                "cd".repeat(32),
                "wave",
                "ef".repeat(32),
                1uL,
                PACK_TITLE,
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
                false,
            )
    }
}
