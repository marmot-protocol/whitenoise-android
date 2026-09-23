package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntRect
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.messages.FOCUSED_OVERLAY_FRAME_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedTextMessagePreview
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageActionMenu
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A 320 dp visible window represents a conversation compressed by the open keyboard. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h320dp-mdpi")
class FocusedMessageImeScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun keyboardOpenLight() = capture("focused_overlay_keyboard_open_light", dark = false, fontScale = 1f)

    @Test
    fun keyboardOpenLargeFontDark() =
        capture("focused_overlay_keyboard_open_large_font_dark", dark = true, fontScale = 2f)

    private fun capture(
        name: String,
        dark: Boolean,
        fontScale: Float,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                MessageActionMenu(
                    expanded = true,
                    anchorBoundsInWindow = IntRect(24, 180, 336, 240),
                    anchorWindowYPx = 210f,
                    canReply = true,
                    canReact = true,
                    canDelete = true,
                    canEdit = true,
                    canForward = true,
                    canSelect = true,
                    canCopyText = true,
                    canSpeak = true,
                    canSelectText = true,
                    canKeepOnScreen = true,
                    canShare = true,
                    canSave = true,
                    quickReactionEmojis = listOf("👍", "❤️", "😂", "😮", "😢", "👏"),
                    onDismissRequest = {},
                    onReact = {},
                    onOpenEmojiPicker = {},
                    onReply = {},
                    onEdit = {},
                    onForward = {},
                    onSelect = {},
                    onSelectText = {},
                    onCopyText = {},
                    onSpeak = {},
                    onKeepOnScreen = {},
                    onShare = {},
                    onSave = {},
                    onInfo = {},
                    onDelete = {},
                    previewDescription = "Lifted message",
                    preview = {
                        FocusedTextMessagePreview(
                            presentation = messageBubblePresentation(deleted = false, mine = false),
                            mine = false,
                            text = "A lifted message above the keyboard",
                            document = null,
                            time = "12:34",
                            status = MessageStatus.Received,
                            showStatus = false,
                        )
                    },
                )
            }
        }
        composeRule.onNodeWithTag(FOCUSED_OVERLAY_FRAME_TEST_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }
}
