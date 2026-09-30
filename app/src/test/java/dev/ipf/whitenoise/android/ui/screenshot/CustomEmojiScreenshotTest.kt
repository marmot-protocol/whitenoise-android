package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.LocalReceivedEmoji
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_PICKER_GRID_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_SUGGESTIONS_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerContent
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiShortcodeSuggestions
import dev.ipf.whitenoise.android.ui.conversation.messages.ReaderSelectablePlainText
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionPillRow
import dev.ipf.whitenoise.android.ui.settings.CustomEmojiContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class CustomEmojiScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private enum class Variant { Light, Dark, LargeRtl }

    @Test fun settingsEmptyLight() = captureSettings(Variant.Light, CustomEmojiSet.Empty, "empty")

    @Test fun settingsEmptyDark() = captureSettings(Variant.Dark, CustomEmojiSet.Empty, "empty")

    @Test fun settingsEmptyLargeRtl() = captureSettings(Variant.LargeRtl, CustomEmojiSet.Empty, "empty")

    @Test fun settingsLight() = captureSettings(Variant.Light, userEmoji(), "populated")

    @Test fun settingsDark() = captureSettings(Variant.Dark, userEmoji(), "populated")

    @Test fun settingsLargeRtl() = captureSettings(Variant.LargeRtl, userEmoji(), "populated")

    @Test fun pickerLight() = capturePicker(Variant.Light)

    @Test fun pickerDark() = capturePicker(Variant.Dark)

    @Test fun pickerLargeRtl() = capturePicker(Variant.LargeRtl)

    @Test fun inlineLight() = captureInline(Variant.Light)

    @Test fun inlineDark() = captureInline(Variant.Dark)

    @Test fun inlineLargeRtl() = captureInline(Variant.LargeRtl)

    @Test fun suggestionsLight() = captureSuggestions(Variant.Light)

    @Test fun suggestionsDark() = captureSuggestions(Variant.Dark)

    @Test fun suggestionsLargeRtl() = captureSuggestions(Variant.LargeRtl)

    private fun artwork(
        fill: Int,
        mark: Int,
    ) = Bitmap
        .createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        .also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawCircle(32f, 32f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            canvas.drawRect(22f, 22f, 42f, 42f, Paint().apply { color = mark })
        }.asImageBitmap()

    private fun userEmoji() =
        CustomEmojiSet(
            listOf(
                CustomEmoji(":party:", File("party.png"), artwork(0xFFE91E63.toInt(), 0xFFFFEB3B.toInt())),
                CustomEmoji(":ship_it:", File("ship_it.png"), artwork(0xFF3F51B5.toInt(), 0xFFFFFFFF.toInt())),
                // A user file overrides the built-in artwork of the same code.
                CustomEmoji(":wn:", File("wn.png"), artwork(0xFF009688.toInt(), 0xFF000000.toInt())),
            ),
        )

    private fun received() =
        ReceivedEmoji(
            art = mapOf(":fromlinux:" to EmojiArt.Decoded(artwork(0xFFFF9800.toInt(), 0xFF4CAF50.toInt()))),
            attachmentIndexes = setOf(0),
        )

    private fun variantName(mode: Variant) =
        when (mode) {
            Variant.Light -> "light"
            Variant.Dark -> "dark"
            Variant.LargeRtl -> "large_rtl"
        }

    private fun setVariantContent(
        mode: Variant,
        custom: CustomEmojiSet,
        content: @Composable () -> Unit,
    ) {
        val rtl = mode == Variant.LargeRtl
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (rtl) 1.6f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalCustomEmoji provides custom,
            ) {
                WhiteNoiseTheme(darkTheme = mode == Variant.Dark) {
                    content()
                }
            }
        }
    }

    private fun captureSettings(
        mode: Variant,
        emoji: CustomEmojiSet,
        state: String,
    ) {
        setVariantContent(mode, emoji) {
            Surface(Modifier.width(360.dp).height(640.dp).testTag("custom-emoji-settings")) {
                CustomEmojiContent(emoji = emoji, onBack = {}, onChoosePhoto = {}, onChooseFile = {}, onRemove = {})
            }
        }
        composeRule
            .onNodeWithTag("custom-emoji-settings")
            .captureRoboImage("src/test/snapshots/custom_emoji_settings_${state}_${variantName(mode)}.png")
    }

    private fun capturePicker(mode: Variant) {
        setVariantContent(mode, userEmoji()) {
            Surface(Modifier.width(360.dp).height(400.dp).testTag("custom-emoji-picker")) {
                EmojiPickerContent(onEmojiPicked = {}, recentEmojis = listOf(":party:", "👍"))
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(EMOJI_PICKER_GRID_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onNodeWithTag("custom-emoji-picker")
            .captureRoboImage("src/test/snapshots/custom_emoji_picker_${variantName(mode)}.png")
    }

    private fun captureInline(mode: Variant) {
        val document =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("Shipped :ship_it: :party: :party:"))),
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Text("Mine :wn:, theirs :fromlinux:, built-in :marmot: "),
                                MarkdownInlineFfi.Code(":party:"),
                            ),
                        ),
                        MarkdownBlockFfi.Paragraph(
                            listOf(MarkdownInlineFfi.Text("Unknown :nothing_here: مرحبا :party:")),
                        ),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(0, 0, 0),
            )
        setVariantContent(mode, userEmoji()) {
            CompositionLocalProvider(LocalReceivedEmoji provides received()) {
                Surface(Modifier.width(360.dp).testTag("custom-emoji-inline")) {
                    Column(Modifier.padding(16.dp)) {
                        MarkdownMessageBody(document)
                        Text("Plain fallback")
                        ReaderSelectablePlainText(":fromlinux: :party: `:party:`", { _, _, _ -> })
                        Text("Reactions")
                        ReactionPillRow(
                            tallies =
                                listOf(
                                    ReactionTally(":party:", 2, mine = true),
                                    ReactionTally(":fromlinux:", 1, mine = false),
                                    ReactionTally(":unknown:", 1, mine = false),
                                ),
                            enabled = true,
                            onOpenDetails = {},
                        )
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag("custom-emoji-inline")
            .captureRoboImage("src/test/snapshots/custom_emoji_inline_${variantName(mode)}.png")
    }

    private fun captureSuggestions(mode: Variant) {
        setVariantContent(mode, userEmoji()) {
            Surface(Modifier.width(360.dp).testTag("custom-emoji-suggestions")) {
                EmojiShortcodeSuggestions(field = TextFieldValue("Ship :pa", TextRange(8)), onPick = {})
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(EMOJI_SUGGESTIONS_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onNodeWithTag("custom-emoji-suggestions")
            .captureRoboImage("src/test/snapshots/custom_emoji_suggestions_${variantName(mode)}.png")
    }
}
