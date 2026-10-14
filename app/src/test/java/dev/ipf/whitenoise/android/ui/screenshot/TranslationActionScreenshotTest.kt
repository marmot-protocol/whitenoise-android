package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_ACTION_MENU_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageActionKind
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageActionMenu
import dev.ipf.whitenoise.android.ui.conversation.messages.messageActionIconRes
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h1000dp-mdpi")
class TranslationActionScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun eachMenuActionHasItsOwnIcon() {
        val icons = MessageActionKind.entries.map(::messageActionIconRes) + R.drawable.ic_delete
        assertEquals(icons.size, icons.toSet().size)
        assertEquals(R.drawable.ic_translate, messageActionIconRes(MessageActionKind.Translate))
    }

    @Test fun translationLight() = capture("translation_action_light.png")

    @Test fun translationDark() = capture("translation_action_dark.png", dark = true)

    @Test fun translationLargeRtl() = capture("translation_action_large_rtl.png", scale = 2f, rtl = true)

    private fun capture(
        name: String,
        dark: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
    ) {
        var translated = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = scale) {
                    MessageActionMenu(
                        expanded = true,
                        anchorBoundsInWindow = null,
                        anchorWindowYPx = 8f,
                        canReply = false,
                        canReact = false,
                        canDelete = false,
                        canEdit = false,
                        canForward = false,
                        canSelect = false,
                        canCopyText = false,
                        canSpeak = true,
                        canSpeakCodeLiterally = true,
                        canTranslate = true,
                        canSelectText = true,
                        canSave = false,
                        canInfo = false,
                        quickReactionEmojis = emptyList(),
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
                        onSave = {},
                        onInfo = {},
                        onDelete = {},
                        onTranslate = { translated = true },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Select text").assertIsDisplayed()
        composeRule.onNodeWithText("Translate").assertIsDisplayed()
        composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).captureRoboImage("src/test/snapshots/$name")
        composeRule.onNodeWithText("Translate").performClick()
        assertEquals(true, translated)
    }
}
