package dev.ipf.whitenoise.android.ui.conversation.messages

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageAttachmentSaveTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun saveActionIsShownForAttachmentAndInvokesCallback() {
        var saveClicks = 0
        renderActionMenu(canSave = true, onSave = { saveClicks++ })

        composeRule.onNodeWithText(string(R.string.shared_media_save)).assertIsDisplayed().performClick()

        assertEquals(1, saveClicks)
    }

    @Test
    fun saveActionIsHiddenWithoutAttachment() {
        renderActionMenu(canSave = false)

        composeRule.onNodeWithText(string(R.string.shared_media_save)).assertDoesNotExist()
    }

    private fun renderActionMenu(
        canSave: Boolean,
        onSave: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageActionMenu(
                    expanded = true,
                    anchorWindowYPx = 0f,
                    alignEnd = false,
                    canReply = false,
                    canReact = false,
                    canDelete = false,
                    canEdit = false,
                    canForward = false,
                    canSelect = false,
                    canCopyText = false,
                    canSelectText = false,
                    canSave = canSave,
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
                    onSave = onSave,
                    onInfo = {},
                    onDelete = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun string(resId: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(resId)
}
