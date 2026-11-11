package dev.ipf.whitenoise.android.ui.conversation.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.state.LoadedSticker
import dev.ipf.whitenoise.android.state.StickerAssetCache
import dev.ipf.whitenoise.android.state.StickerKey
import dev.ipf.whitenoise.android.state.StickerLibrary
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class StickerScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** A loaded sticker, one waiting for a tap and one that failed, side by side in the timeline size. */
    @Test fun messageStickerStatesAmoled() {
        val loaded = cache()
        val failing = cache(failing = true)
        composeRule.setContent {
            Frame(darkTheme = true, amoled = true, height = null) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val side = Modifier.size(110.dp)
                    for ((index, name) in listOf("wave", "nod", "oops").withIndex()) {
                        StickerImage(
                            StickerKey.from(sticker(name, index)),
                            loadsAutomatically = name != "nod",
                            cache = if (name == "oops") failing else loaded,
                            modifier = side,
                        )
                    }
                }
            }
        }
        capture("sticker_message_states_amoled")
    }

    /** The picker shows pack tabs, the selected pack's grid and the server disclosure. */
    @Test fun pickerLight() = picker(darkTheme = false, amoled = false, name = "sticker_picker_light")

    @Test fun pickerAmoled() = picker(darkTheme = true, amoled = true, name = "sticker_picker_amoled")

    /** With no installed packs the picker points to the pack browser. */
    @Test fun pickerEmptyAmoled() {
        val library = library(installed = emptyList())
        composeRule.setContent {
            Frame(darkTheme = true, amoled = true) {
                StickerPickerPage(library, cache(), onBrowse = {}, onSend = {}, onClose = {})
            }
        }
        capture("sticker_picker_empty_amoled")
    }

    /** When the installed packs can't be read the picker offers a retry instead of a spinner or the empty state. */
    @Test fun pickerLoadFailedAmoled() {
        composeRule.setContent {
            Frame(darkTheme = true, amoled = true) {
                StickerPickerPage(unreadableLibrary(), cache(), onBrowse = {}, onSend = {}, onClose = {})
            }
        }
        capture("sticker_picker_load_failed_amoled")
    }

    /** When cached packs can't be read Discover says so and offers a retry, in RTL at a large font. */
    @Test fun browserLoadFailedRtlLargeFont() {
        composeRule.setContent {
            Frame(darkTheme = true, amoled = true, rtlLarge = true) {
                StickerBrowserPage(unreadableLibrary(), { _, _, _ -> }, onOpen = {}, onBack = {}, onClose = {})
            }
        }
        capture("sticker_browser_load_failed_rtl_large")
    }

    /** The browser lists installed and discoverable packs; the link field stays behind its top-bar button. */
    @Test fun browserAmoled() = browser(rtlLarge = false, name = "sticker_browser_amoled")

    /** The top-bar link button opens the paste field above the packs. */
    @Test
    fun browserLinkOpenAmoled() = browser(rtlLarge = false, name = "sticker_browser_link_open_amoled", linkOpen = true)

    @Test fun browserRtlLargeFont() = browser(rtlLarge = true, name = "sticker_browser_rtl_large")

    /** A pack's detail shows its tappable author, description, send hint, stickers, share and add actions. */
    @Test fun packDetailLight() {
        val pack = pack("cats", "Office cats", installed = false, description = "Cats who attend standups.")
        composeRule.setContent {
            Frame(darkTheme = false, amoled = false) {
                StickerPackDetailPage(
                    pack,
                    library(installed = emptyList()),
                    cache(),
                    "Alice",
                    onOpenAuthor = {},
                    onSend = {},
                    onFailure = { _, _, _ -> },
                    onBack = {},
                    onClose = {},
                )
            }
        }
        capture("sticker_pack_detail_light")
    }

    private fun picker(
        darkTheme: Boolean,
        amoled: Boolean,
        name: String,
    ) {
        val library = library(installed = listOf(pack("cats", "Office cats"), pack("dogs", "Good dogs")))
        composeRule.setContent {
            Frame(darkTheme = darkTheme, amoled = amoled) {
                StickerPickerPage(library, cache(), onBrowse = {}, onSend = {}, onClose = {})
            }
        }
        capture(name)
    }

    private fun browser(
        rtlLarge: Boolean,
        name: String,
        linkOpen: Boolean = false,
    ) {
        val library =
            library(
                installed = listOf(pack("cats", "Office cats")),
                discoverable =
                    listOf(pack("frogs", "Frog moods", installed = false), pack("space", "Space", installed = false)),
            )
        composeRule.setContent {
            Frame(darkTheme = true, amoled = true, rtlLarge = rtlLarge) {
                StickerBrowserPage(library, { _, _, _ -> }, onOpen = {}, onBack = {}, onClose = {})
            }
        }
        if (linkOpen) composeRule.onNodeWithTag("sticker.link.toggle").performClick()
        capture(name)
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sticker-frame").captureRoboImage("src/test/snapshots/$name.png")
    }

    @Composable
    private fun Frame(
        darkTheme: Boolean,
        amoled: Boolean,
        rtlLarge: Boolean = false,
        height: Int? = 640,
        content: @Composable () -> Unit,
    ) {
        val direction = if (rtlLarge) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = if (rtlLarge) 2f else 1f) {
                Surface(Modifier.width(360.dp).testTag("sticker-frame")) {
                    Column(if (height != null) Modifier.height(height.dp) else Modifier) { content() }
                }
            }
        }
    }

    private companion object {
        val COLORS = intArrayOf(0xFFF2B33D.toInt(), 0xFF5FB3A1.toInt(), 0xFFE0607E.toInt(), 0xFF7A8CF0.toInt())

        fun coordinate(id: String) = "30031:${"cd".repeat(32)}:$id"

        fun sticker(
            shortcode: String,
            index: Int,
            packId: String = "cats",
        ) = StickerFfi(
            coordinate(packId),
            shortcode,
            "https://example.invalid/$shortcode.webp",
            index.toString(16).padStart(2, '0').repeat(32),
            "image/webp",
            null,
            null,
            null,
            null,
        )

        fun pack(
            id: String,
            title: String,
            installed: Boolean = true,
            description: String? = null,
        ): StickerPackFfi {
            val stickers = (0 until 7).map { sticker("$id$it", it, id) }
            return StickerPackFfi(
                coordinate(id),
                "cd".repeat(32),
                id,
                "ef".repeat(32),
                1uL,
                title,
                description,
                null,
                stickers,
                null,
                installed,
            )
        }

        fun library(
            installed: List<StickerPackFfi>,
            discoverable: List<StickerPackFfi> = emptyList(),
        ) = StickerLibrary(
            packs = { installedOnly, _ -> if (installedOnly) installed else installed + discoverable },
            syncPacks = {},
            fetchPack = { error("No lookup in screenshots") },
            installPack = { error("No install in screenshots") },
            uninstallPack = { error("No uninstall in screenshots") },
        )

        /** A library whose pack reads always fail. */
        fun unreadableLibrary() =
            StickerLibrary(
                packs = { _, _ -> error("Pack storage unavailable in screenshots") },
                syncPacks = {},
                fetchPack = { error("No lookup in screenshots") },
                installPack = { error("No install in screenshots") },
                uninstallPack = { error("No uninstall in screenshots") },
            )

        /** Images load immediately: a colored disc per sticker, or nothing when [failing]. */
        fun cache(failing: Boolean = false) =
            StickerAssetCache<ImageBitmap>(
                scope = CoroutineScope(Dispatchers.Unconfined),
                load = { key ->
                    if (failing) {
                        null
                    } else {
                        val color = COLORS[Math.floorMod(key.shortcode.hashCode(), COLORS.size)]
                        LoadedSticker(disc(color), sticker(key.shortcode, 0))
                    }
                },
                maxBytes = Long.MAX_VALUE,
                sizeOf = { 1 },
            )

        fun disc(color: Int): ImageBitmap {
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawCircle(48f, 48f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            return bitmap.asImageBitmap()
        }
    }
}
