package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.messages.FOCUSED_OVERLAY_FRAME_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedMessageAction
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedMessageActions
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
    fun keyboardOpenLargeFontDark() {
        capture("focused_overlay_keyboard_open_large_font_dark", dark = true, fontScale = 2f)
    }

    @Test
    fun longTextAtLargeFontLeavesActionsVisible() {
        capture("focused_overlay_keyboard_long_text_large_font_dark", dark = true, fontScale = 2f, longText = true)
    }

    @Test
    fun captionedMediaAtLargeFontKeepsFooterVisible() {
        capture("focused_overlay_keyboard_captioned_media_large_font_dark", dark = true, fontScale = 2f, media = true)
    }

    @Test
    fun tallMediaKeepsActionsVisible() {
        composeRule.setContent {
            WhiteNoiseTheme {
                FocusedMessageActions(
                    sourceBounds = IntRect(0, 180, 360, 240),
                    touchY = 210f,
                    mine = true,
                    actions =
                        List(6) { index ->
                            FocusedMessageAction("Action $index", null, true, false, {}, {})
                        },
                    quickReactions = listOf("👍", "❤️"),
                    canReact = true,
                    selectedReactions = emptySet(),
                    previewDescription = "Tall media",
                    previewReady = true,
                    preview = {
                        Box(Modifier.size(200.dp, 400.dp).background(MaterialTheme.colorScheme.primaryContainer)) {
                            Text("Portrait media")
                        }
                    },
                    onReact = {},
                    onMoreReactions = {},
                    onDismiss = {},
                )
            }
        }
        composeRule
            .onNodeWithTag(FOCUSED_OVERLAY_FRAME_TEST_TAG)
            .captureRoboImage("src/test/snapshots/focused_overlay_tall_media_ime.png")
    }

    private fun capture(
        name: String,
        dark: Boolean,
        fontScale: Float,
        longText: Boolean = false,
        media: Boolean = false,
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
                            text =
                                if (longText) {
                                    (1..8).joinToString(" ") { "A longer message above the keyboard." }
                                } else {
                                    "A lifted message above the keyboard"
                                },
                            document = null,
                            time = "12:34",
                            status = MessageStatus.Received,
                            showStatus = false,
                            reply = if (longText || media) ({ Text("Quoted reply with two lines of context") }) else null,
                            media =
                                if (media) {
                                    {
                                        Box(
                                            Modifier
                                                .size(200.dp, 400.dp)
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                        ) { Text("Portrait media") }
                                    }
                                } else {
                                    null
                                },
                        )
                    },
                )
            }
        }
        composeRule.onNodeWithTag(FOCUSED_OVERLAY_FRAME_TEST_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }
}
