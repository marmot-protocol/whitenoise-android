package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.media.GroupEmojiImageRenderer
import dev.ipf.whitenoise.android.ui.group.GROUP_EMOJI_IMAGE_PICKER_TAG
import dev.ipf.whitenoise.android.ui.group.GroupEmojiImagePickerSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupEmojiImagePickerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun oneEmojiPreviewLight() {
        render(darkTheme = false)
        select("😀")
        awaitPreview("Generated group image preview using 😀")

        composeRule
            .onNodeWithTag(GROUP_EMOJI_IMAGE_PICKER_TAG)
            .captureRoboImage("src/test/snapshots/group_emoji_image_one_light.png")
    }

    @Test
    fun twoEmojiPreviewDark() {
        render(darkTheme = true)
        select("😀")
        select("🚀")
        awaitPreview("Generated group image preview using 😀 🚀")

        composeRule
            .onNodeWithTag(GROUP_EMOJI_IMAGE_PICKER_TAG)
            .captureRoboImage("src/test/snapshots/group_emoji_image_two_dark.png")
    }

    @Test
    fun twoEmojiPreviewLargeFont() {
        render(darkTheme = false, fontScale = 1.6f)
        select("😀")
        select("🚀")
        awaitPreview("Generated group image preview using 😀 🚀")

        composeRule
            .onNodeWithTag(GROUP_EMOJI_IMAGE_PICKER_TAG)
            .captureRoboImage("src/test/snapshots/group_emoji_image_two_large_font.png")
    }

    private fun render(
        darkTheme: Boolean,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, fontScale = fontScale) {
                GroupEmojiImagePickerSheet(
                    applyInFlight = false,
                    recentEmojis = listOf("😀", "🚀"),
                    onEmojiUsed = {},
                    onApply = {},
                    onDismiss = {},
                    renderer = { GroupEmojiImageRenderer.render(it, hasGlyph = { _, _ -> true }) },
                )
            }
        }
    }

    private fun select(emoji: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(emoji).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(emoji).onFirst().performClick()
    }

    private fun awaitPreview(description: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithContentDescription(description).fetchSemanticsNode() }.isSuccess
        }
    }
}
