package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.InstalledEmojiSetStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.EmojiSetNotice
import dev.ipf.whitenoise.android.state.EmojiSetsState
import dev.ipf.whitenoise.android.state.emojiSetFixture
import dev.ipf.whitenoise.android.state.installedEmojiFixture
import dev.ipf.whitenoise.android.ui.BuiltinEmojiSet
import dev.ipf.whitenoise.android.ui.BuiltinEmojiSetChoices
import dev.ipf.whitenoise.android.ui.ComposerEmojiLibrary
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerContent
import dev.ipf.whitenoise.android.ui.settings.EMOJI_SETS_REFRESH_TEST_TAG
import dev.ipf.whitenoise.android.ui.settings.EMOJI_SET_LOAD_TEST_TAG
import dev.ipf.whitenoise.android.ui.settings.EmojiSetImageSource
import dev.ipf.whitenoise.android.ui.settings.EmojiSetImages
import dev.ipf.whitenoise.android.ui.settings.EmojiSetsContent
import dev.ipf.whitenoise.android.ui.settings.builtinEmojiSetRowTestTag
import dev.ipf.whitenoise.android.ui.settings.emojiSetTileTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Version review and composer artwork in both themes and at enlarged RTL text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class EmojiSetsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** The browser groups the installed snapshot and a relay result under Installed and Discover (#3882). */
    @Test fun browserLight() = preview("browser_light", showPreview = false)

    /** The pre-installed sets list above installed sets, each with its own picker switch. */
    @Test fun browserBuiltinLight() = builtinBrowser("browser_builtin_light")

    /** A removed pre-installed set says it still renders in messages. */
    @Test fun browserBuiltinHiddenDark() = builtinBrowser("browser_builtin_hidden_dark", dark = true, hide = true)

    /** The pre-installed rows mirrored with large text. */
    @Test fun browserBuiltinRtlLarge() = builtinBrowser("browser_builtin_rtl_large", rtl = true)

    /** A search with no matches says so; the pre-installed rows return once the search is cleared. */
    @Test fun emptySearchHidesBuiltinRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val choices = BuiltinEmojiSetChoices(context)
        runBlocking { choices.load() }
        setTheme(dark = false, rtl = false) {
            EmojiSetsContent(
                state = EmojiSetsState(searched = true),
                onBack = {},
                onSearch = {},
                onShow = {},
                images = null,
                onInstall = {},
                onCancel = {},
                onRemove = {},
                builtinSets = choices,
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.emoji_sets_empty)).assertExists()
        composeRule.onNodeWithTag(builtinEmojiSetRowTestTag(BuiltinEmojiSet.Marmot)).assertDoesNotExist()
    }

    /** Unreachable relays show their notice instead of "no sets", with Refresh in the top bar (#3882). */
    @Test fun browserDiscoveryIncompleteDark() {
        failedBrowser("discovery_incomplete_dark", EmojiSetNotice.DiscoveryIncomplete, dark = true)
    }

    /** A failed discovery in a mirrored layout with large text. */
    @Test fun browserDiscoveryErrorRtlLarge() {
        failedBrowser("discovery_error_rtl_large", EmojiSetNotice.Error, rtl = true)
    }

    /** A discovered set opens with blank tiles, its image hosts and Load emoji; no shortcodes or hosts per emoji. */
    @Test fun previewLight() = gallery("preview_light", requested = false)

    /** An installed preview with update and removal available still waits for consent to load the new version. */
    @Test fun previewDark() = gallery("preview_dark", dark = true, requested = false, installed = updateAvailable)

    /** The two-button update row and the unloaded grid, mirrored with large text. */
    @Test fun previewRtlLarge() =
        gallery("preview_rtl_large", rtl = true, requested = false, installed = updateAvailable)

    /** After Load emoji: loaded art, pulsing blanks for tiles in flight and a retry mark for a failed one. */
    @Test fun previewLoadingLight() = gallery("preview_loading_light", requested = true, pending = setOf("owl", "bee"))

    /** An installed snapshot of this exact version renders from the device without asking. */
    @Test fun previewInstalledDark() =
        gallery("preview_installed_dark", dark = true, requested = false, installed = installedHere)

    /** The loaded grid mirrored with large text, so fewer, larger tiles fit a row. */
    @Test fun previewLoadedRtlLarge() = gallery("preview_loaded_rtl_large", rtl = true, requested = true)

    /** Install progress replaces the actions with MDK's count and Cancel. */
    @Test fun previewInstallingLight() =
        gallery(
            "preview_installing_light",
            requested = true,
            progress = dev.ipf.marmotkit.EmojiSetInstallProgressFfi(5u, 12u, 0u),
        )

    /** Loading starts only from Load emoji, and a failed tile retries on tap. */
    @Test fun loadEmojiAsksFirstAndRetriesFailures() {
        val source = GallerySource(failing = setOf("frog"))
        val images = EmojiSetImages(galleryFixture(), source) { GalleryArt[it.first().toInt()] }
        setTheme(dark = false, rtl = false) { galleryContent(images) }
        composeRule.waitForIdle()
        assertEquals("Nothing loads before the user asks", emptyList<String>(), source.requests)
        composeRule.onNodeWithTag(EMOJI_SET_LOAD_TEST_TAG).performClick()
        composeRule.waitForIdle()
        assertTrue("The cover loads after the tap", "cover" in source.requests)
        composeRule.onNodeWithTag(emojiSetTileTestTag("fox")).assertContentDescriptionEquals(":fox:")
        val fox = composeRule.onNodeWithTag(emojiSetTileTestTag("fox")).fetchSemanticsNode().size.width
        val owl = composeRule.onNodeWithTag(emojiSetTileTestTag("owl")).fetchSemanticsNode().size.width
        assertTrue("Tiles in a row share its width equally ($fox, $owl)", fox > 0 && fox == owl)
        assertEquals("A loaded tile is fetched exactly once", 1, source.requests.count { it == "fox" })
        assertEquals("A failed tile does not reload by itself", 1, source.requests.count { it == "frog" })
        composeRule.onNodeWithTag(emojiSetTileTestTag("frog")).performClick()
        composeRule.waitForIdle()
        assertEquals("Tapping a failed tile retries it once", 2, source.requests.count { it == "frog" })
    }

    /** Untitled sets with machine-made identifiers never show the hex as their name. */
    @Test fun untitledHexSetIsNamedUntitled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hex = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val set = galleryFixture().copy(title = null, identifier = hex)
        setTheme(dark = false, rtl = false) {
            EmojiSetsContent(
                state = EmojiSetsState(sets = listOf(set)),
                onBack = {},
                onSearch = {},
                onShow = {},
                images = null,
                onInstall = {},
                onCancel = {},
                onRemove = {},
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.emoji_sets_untitled)).assertExists()
        composeRule.onNodeWithText(hex, substring = true).assertDoesNotExist()
    }

    private val updateAvailable = InstalledEmojiSetStateFfi("previous", 0u, 0u, 12u, 100u, true)
    private val installedHere = InstalledEmojiSetStateFfi("gallery", 0u, 0u, 12u, 100u, false)

    @Suppress("LongParameterList")
    private fun gallery(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        requested: Boolean,
        installed: InstalledEmojiSetStateFfi? = null,
        pending: Set<String> = emptySet(),
        progress: dev.ipf.marmotkit.EmojiSetInstallProgressFfi? = null,
    ) {
        val set = galleryFixture().copy(installed = installed)
        val source = GallerySource(failing = setOf("frog"), pending = pending)
        val images = EmojiSetImages(set, source) { GalleryArt[it.first().toInt()] }
        if (requested) images.request()
        // The pulsing blanks never settle, so frames advance by hand.
        composeRule.mainClock.autoAdvance = false
        setTheme(dark, rtl) { galleryContent(images, progress) }
        composeRule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)
        ShadowLooper.idleMainLooper()
        composeRule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/emoji_sets_$name.png")
    }

    @Composable
    private fun galleryContent(
        images: EmojiSetImages,
        progress: dev.ipf.marmotkit.EmojiSetInstallProgressFfi? = null,
    ) {
        EmojiSetsContent(
            state = EmojiSetsState(sets = listOf(images.set), preview = images.set, progress = progress),
            onBack = {},
            onSearch = {},
            onShow = {},
            images = images,
            onInstall = {},
            onCancel = {},
            onRemove = {},
        )
    }

    /** The update check waits for the opening sync, which would otherwise drop the lookup. */
    @Test fun checkForUpdateWaitsForOpeningSync() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val set = emojiSetFixture().copy(installed = InstalledEmojiSetStateFfi("previous", 0u, 0u, 1u, 100u, false))
        setTheme(dark = false, rtl = false) {
            EmojiSetsContent(
                state = EmojiSetsState(sets = listOf(set), preview = set, discovering = true),
                onBack = {},
                onSearch = {},
                onShow = {},
                images = null,
                onInstall = {},
                onCancel = {},
                onRemove = {},
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.emoji_sets_check_update)).assertIsNotEnabled()
    }

    /** The composer grid with a thumbnail loaded from the installed set. */
    @Test fun pickerLight() = picker("picker_light")

    /** The installed thumbnail in dark mode. */
    @Test fun pickerDark() = picker("picker_dark", dark = true)

    /** The installed thumbnail in a mirrored grid with large text. */
    @Test fun pickerRtlLarge() = picker("picker_rtl_large", rtl = true)

    private fun preview(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        showPreview: Boolean = true,
    ) {
        val set = emojiSetFixture()
        val installed = InstalledEmojiSetStateFfi("previous", 0u, 0u, 1u, 100u, true)
        val selected = if (dark) set.copy(installed = installed) else set
        val discovered =
            set.copy(coordinate = "30030:${"2".repeat(64)}:ocean", identifier = "ocean", title = "Ocean waves")
        val sets = listOf(set.copy(installed = installed)) + listOfNotNull(discovered.takeUnless { showPreview })
        setTheme(dark, rtl) {
            EmojiSetsContent(
                state =
                    EmojiSetsState(
                        sets = sets,
                        preview = selected.takeIf { showPreview },
                    ),
                onBack = {},
                onSearch = {},
                onShow = {},
                images = null,
                onInstall = {},
                onCancel = {},
                onRemove = {},
            )
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/emoji_sets_$name.png")
    }

    private fun builtinBrowser(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        hide: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val choices = BuiltinEmojiSetChoices(context)
        runBlocking { choices.load() }
        choices.setHidden(BuiltinEmojiSet.Marmot, hide)
        val installed = InstalledEmojiSetStateFfi("previous", 0u, 0u, 1u, 100u, false)
        setTheme(dark, rtl) {
            EmojiSetsContent(
                state = EmojiSetsState(sets = listOf(emojiSetFixture().copy(installed = installed))),
                onBack = {},
                onSearch = {},
                onShow = {},
                images = null,
                onInstall = {},
                onCancel = {},
                onRemove = {},
                builtinSets = choices,
            )
        }
        composeRule.onNodeWithTag(builtinEmojiSetRowTestTag(BuiltinEmojiSet.Marmot)).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/emoji_sets_$name.png")
        composeRule.onNodeWithTag(builtinEmojiSetRowTestTag(BuiltinEmojiSet.Marmot)).performClick()
        assertEquals("The switch flips the stored choice", !hide, BuiltinEmojiSet.Marmot in choices.hidden)
    }

    /** Golden: an empty browser showing [notice] in place of the empty-library message. */
    private fun failedBrowser(
        name: String,
        notice: EmojiSetNotice,
        dark: Boolean = false,
        rtl: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val searches = mutableListOf<String>()
        setTheme(dark, rtl) {
            EmojiSetsContent(
                state = EmojiSetsState(notice = notice),
                onBack = {},
                onSearch = { searches += it },
                onShow = {},
                images = null,
                onInstall = {},
                onCancel = {},
                onRemove = {},
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.emoji_sets_empty)).assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/emoji_sets_$name.png")
        composeRule.onNodeWithTag(EMOJI_SETS_REFRESH_TEST_TAG).performClick()
        assertEquals("Refresh retries discovery with the current query", listOf(""), searches)
    }

    private fun picker(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
    ) {
        val entry = installedEmojiFixture("forest-fox", "forest")
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8426.toInt() }
        Canvas(image).apply {
            drawCircle(16f, 16f, 14f, paint)
            paint.color = Color.WHITE
            drawOval(6f, 15f, 26f, 27f, paint)
            paint.color = Color.BLACK
            drawCircle(11f, 12f, 2f, paint)
            drawCircle(21f, 12f, 2f, paint)
            drawCircle(16f, 20f, 2f, paint)
        }
        val art = EmojiArt.Decoded(image.asImageBitmap())
        val library =
            dev.ipf.whitenoise.android.ui
                .InstalledEmojiPicker(mapOf(":forest-fox:" to entry)) { art }
        setTheme(dark, rtl) {
            Surface(Modifier.height(420.dp).testTag("emoji-set-picker")) {
                ComposerEmojiLibrary(library) {
                    EmojiPickerContent(onEmojiPicked = {})
                }
            }
        }
        // The standard emoji load off the main thread, so wait for their Smileys tab as well as the installed set.
        val smileys =
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.emoji_category_smileys_people)
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            listOf("forest fox", smileys).all {
                composeRule.onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.onNodeWithTag("emoji-set-picker").captureRoboImage("src/test/snapshots/emoji_sets_$name.png")
    }

    /** Serves drawn art by shortcode; [failing] codes throw and [pending] ones never answer. */
    private class GallerySource(
        val failing: Set<String> = emptySet(),
        val pending: Set<String> = emptySet(),
    ) : EmojiSetImageSource {
        val requests = mutableListOf<String>()

        override suspend fun cover(set: dev.ipf.marmotkit.EmojiSetFfi): ByteArray {
            requests += "cover"
            return byteArrayOf(0)
        }

        override suspend fun emoji(
            set: dev.ipf.marmotkit.EmojiSetFfi,
            shortcode: String,
            local: Boolean,
        ): ByteArray {
            requests += shortcode
            // Suspend like the native fetch, so a tile recomposes as Loading before its bytes arrive.
            kotlinx.coroutines.yield()
            if (shortcode in pending) kotlinx.coroutines.awaitCancellation()
            check(shortcode !in failing) { "host refused" }
            return byteArrayOf(GalleryCodes.indexOf(shortcode).toByte())
        }
    }

    private fun setTheme(
        dark: Boolean,
        rtl: Boolean,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (rtl) 2f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, content = content)
            }
        }
    }
}

private const val FRAME_SETTLE_MS = 500L

private val GalleryCodes =
    listOf("fox", "owl", "frog", "bee", "cat", "fish", "bear", "snail", "duck", "pig", "mouse", "crab")

private val GalleryColors =
    listOf(
        0xFFFF8426, 0xFF8D6E63, 0xFF66BB6A, 0xFFFFCA28, 0xFFFFA726, 0xFF29B6F6,
        0xFF795548, 0xFFAB47BC, 0xFFFFEE58, 0xFFF48FB1, 0xFFB0BEC5, 0xFFEF5350,
    )

/** Simple drawn faces, one per gallery shortcode. */
private val GalleryArt: List<EmojiArt> =
    GalleryColors.map { color ->
        val image = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toInt() }
        Canvas(image).apply {
            drawCircle(24f, 24f, 22f, paint)
            paint.color = Color.BLACK
            drawCircle(16f, 19f, 3f, paint)
            drawCircle(32f, 19f, 3f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            drawArc(14f, 20f, 34f, 36f, 20f, 140f, false, paint)
        }
        EmojiArt.Decoded(image.asImageBitmap())
    }

private fun galleryFixture() =
    emojiSetFixture().copy(
        identifier = "gallery-friends",
        eventIdHex = "gallery",
        title = "Garden friends",
        description = "Twelve hand-drawn critters for reactions and replies.",
        imageUrl = "https://cdn.emoji.example/cover.png",
        emoji = GalleryCodes.map { dev.ipf.marmotkit.EmojiSetEmojiFfi(it, "https://cdn.emoji.example/$it.png") },
    )
