package dev.ipf.whitenoise.android.ui.screenshot

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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.InstalledEmojiSetStateFfi
import dev.ipf.whitenoise.android.state.EmojiSetsState
import dev.ipf.whitenoise.android.state.emojiSetFixture
import dev.ipf.whitenoise.android.state.installedEmojiFixture
import dev.ipf.whitenoise.android.ui.ComposerEmojiLibrary
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerContent
import dev.ipf.whitenoise.android.ui.settings.EmojiSetsContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Version review and composer artwork in both themes and at enlarged RTL text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class EmojiSetsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** The available-set browser with an installed snapshot. */
    @Test fun browserLight() = preview("browser_light", showPreview = false)

    /** An unopened set preview and its image-host disclosure. */
    @Test fun previewLight() = preview("preview_light")

    /** An installed preview with update and removal available. */
    @Test fun previewDark() = preview("preview_dark", dark = true)

    /** The same preview with a mirrored layout and large text. */
    @Test fun previewRtlLarge() = preview("preview_rtl_large", rtl = true)

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
        setTheme(dark, rtl) {
            EmojiSetsContent(
                state =
                    EmojiSetsState(
                        sets = listOf(set.copy(installed = installed)),
                        preview = selected.takeIf { showPreview },
                    ),
                onBack = {},
                onSearch = {},
                onShow = {},
                onLoadArtwork = {},
                onInstall = {},
                onCancel = {},
                onRemove = {},
            )
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/emoji_sets_$name.png")
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
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("forest fox").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("emoji-set-picker").captureRoboImage("src/test/snapshots/emoji_sets_$name.png")
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
