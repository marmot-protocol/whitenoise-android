package dev.ipf.whitenoise.android.ui.conversation.stickers

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.StickerLibrary
import dev.ipf.whitenoise.android.state.WhiteNoiseStickerPacks
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pack browser offers White Noise's own packs, and only the person's Add installs one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhiteNoiseStickerSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Opening the browser lists both packs under White Noise without installing them; a tap opens one to add. */
    @Test
    fun whiteNoisePacksAreOfferedNotInstalled() {
        val (originalsAt, marmotAt) = WhiteNoiseStickerPacks.coordinates
        val offered = listOf(pack(originalsAt, "White Noise Originals"), pack(marmotAt, "Marmot"))
        var installs = 0
        val opened = mutableListOf<String>()
        val open: (StickerPackFfi) -> Unit = { opened += it.coordinate }
        val library =
            StickerLibrary(
                packs = { _, _ -> emptyList() },
                syncPacks = {},
                fetchPack = { input -> offered.first { WhiteNoiseStickerPacks.link(it.coordinate) == input } },
                installPack = {
                    installs++
                    error("unused")
                },
                uninstallPack = { error("unused") },
            )

        composeRule.setContent {
            WhiteNoiseTheme {
                // The page emits its top bar and list as siblings for the sheet's column to stack.
                Column {
                    StickerBrowserPage(library, { _, _, _ -> }, onOpen = open, onBack = {}, onClose = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app.getString(R.string.white_noise)).assertExists()
        composeRule.onNodeWithText("White Noise Originals").assertExists()
        composeRule.onNodeWithText("Marmot").performClick()

        assertEquals(listOf(marmotAt), opened)
        assertEquals(0, installs)
    }

    /** A lookup that failed offline is retried by Refresh, so the section appears without reopening the browser. */
    @Test
    fun refreshRetriesAFailedWhiteNoiseLookup() {
        val offered = WhiteNoiseStickerPacks.coordinates.map { pack(it, it.substringAfterLast(':')) }
        var online = false
        val library =
            StickerLibrary(
                packs = { _, _ -> emptyList() },
                syncPacks = {},
                fetchPack = { input ->
                    check(online) { "network unreachable" }
                    offered.first { WhiteNoiseStickerPacks.link(it.coordinate) == input }
                },
                installPack = { error("unused") },
                uninstallPack = { error("unused") },
            )

        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    StickerBrowserPage(library, { _, _, _ -> }, onOpen = {}, onBack = {}, onClose = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app.getString(R.string.white_noise)).assertDoesNotExist()

        online = true
        composeRule.onNodeWithContentDescription(app.getString(R.string.refresh)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app.getString(R.string.white_noise)).assertExists()
        composeRule.onNodeWithText("marmot").assertExists()
    }

    /** A pack at [coordinate] titled [title], not installed. */
    private fun pack(
        coordinate: String,
        title: String,
    ) = StickerPackFfi(
        coordinate,
        WhiteNoiseStickerPacks.AUTHOR_PUBKEY_HEX,
        coordinate.substringAfterLast(':'),
        "ef".repeat(32),
        1uL,
        title,
        null,
        null,
        emptyList(),
        null,
        false,
    )
}
