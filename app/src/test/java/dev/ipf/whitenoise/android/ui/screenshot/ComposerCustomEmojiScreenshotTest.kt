package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A draft's bundled and custom `:shortcode:` emoji draw as artwork in the composer, like in sent messages. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ComposerCustomEmojiScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerCustomEmojiLight() = capture(darkTheme = false, largeRtl = false, "composer_custom_emoji_light.png")

    @Test
    fun composerCustomEmojiDark() = capture(darkTheme = true, largeRtl = false, "composer_custom_emoji_dark.png")

    @Test
    fun composerCustomEmojiLargeRtl() = capture(darkTheme = false, largeRtl = true, "composer_custom_emoji_large_rtl.png")

    private fun capture(
        darkTheme: Boolean,
        largeRtl: Boolean,
        name: String,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (largeRtl) 1.6f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalCustomEmoji provides CustomEmojiSet(listOf(CustomEmoji(":party:", File("party.png"), artwork()))),
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(Modifier.width(360.dp).testTag(TAG)) {
                        ComposerPill(
                            actionColors = accountActionColors(appState = null),
                            textFieldValue = TextFieldValue(DRAFT, selection = TextRange(DRAFT.length)),
                            composerFocus = FocusRequester(),
                            emojiPickerOpen = false,
                            onValueChange = {},
                            onEmojiPickerToggle = {},
                            onAttachmentsToggle = {},
                            attachmentSheetOpen = false,
                            onPickFromGallery = null,
                            onPickDocument = null,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name")
    }

    private fun artwork() =
        Bitmap
            .createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            .also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.drawCircle(32f, 32f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE91E63.toInt() })
                canvas.drawRect(22f, 22f, 42f, 42f, Paint().apply { color = 0xFFFFEB3B.toInt() })
            }.asImageBitmap()

    private companion object {
        const val TAG = "composer-custom-emoji"

        // A raw `:unknown:` code and a code span stay text; `:marmot:` and `:party:` draw artwork.
        const val DRAFT = "Ship it :marmot: :party: now :unknown: `:party:`"
    }
}
