package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationProfileSchedule
import dev.ipf.whitenoise.android.ui.common.EmojiSheetDragHandleHeight
import dev.ipf.whitenoise.android.ui.common.EmojiSheetFieldGap
import dev.ipf.whitenoise.android.ui.common.EmojiSheetMinimumPickerHeight
import dev.ipf.whitenoise.android.ui.common.GroupNameEmojiField
import dev.ipf.whitenoise.android.ui.common.rememberEmojiSheetFieldAnchor
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_PICKER_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.settings.NotificationProfileEditActions
import dev.ipf.whitenoise.android.ui.settings.NotificationProfileEditContent
import dev.ipf.whitenoise.android.ui.settings.NotificationProfileEditFormState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An emoji sheet opened from a text field sits underneath the field at the full height below it (#3601). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class EmojiSheetBelowFieldScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun profileNameLight() = captureProfileName("${PROFILE_NAME}_light", amoled = false)

    @Test fun profileNameAmoled() = captureProfileName("${PROFILE_NAME}_amoled", amoled = true)

    @Test fun lowGroupNameScrollsUpLight() = captureLowGroupName("${GROUP_NAME}_light", amoled = false)

    @Test fun lowGroupNameScrollsUpAmoled() = captureLowGroupName("${GROUP_NAME}_amoled", amoled = true)

    private fun captureProfileName(
        name: String,
        amoled: Boolean,
    ) {
        val form =
            NotificationProfileEditFormState(
                isNew = false,
                name = TextFieldState("Sleep"),
                allowedChatCount = 2,
                allowMentions = true,
                accountName = "Alice",
                schedule = NotificationProfileSchedule.Default,
                canSave = true,
                canDelete = true,
                error = null,
            )
        val actions = NotificationProfileEditActions({}, {}, {}, {}, {}, {}, {}, {}, {})
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = amoled, amoled = amoled) { NotificationProfileEditContent(form, actions) }
        }
        openPicker()
        assertSheetBelow("notification-profile.name")
        captureScreenRoboImage("src/test/snapshots/$name.png")
    }

    /** A field low on a scrolling form scrolls up first, so the picker below it gets a usable height. */
    private fun captureLowGroupName(
        name: String,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = amoled, amoled = amoled) {
                val anchor = rememberEmojiSheetFieldAnchor()
                var open by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text("Group details above the name", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(540.dp))
                        GroupNameEmojiField(
                            value = TextFieldValue("Marmot team"),
                            onValueChange = {},
                            label = "Group name",
                            emojiPickerOpen = open,
                            onEmojiPickerClick = { open = true },
                            enabled = true,
                            modifier = Modifier.fillMaxWidth().testTag(GROUP_NAME_TAG),
                            emojiAnchor = anchor,
                        )
                        Spacer(Modifier.height(600.dp))
                    }
                }
                if (open) {
                    EmojiPickerSheet(onDismissRequest = { open = false }, onEmojiPicked = {}, fieldAnchor = anchor)
                }
            }
        }
        val before = composeRule.onNodeWithTag(GROUP_NAME_TAG).getBoundsInRoot().bottom
        openPicker()
        val after = composeRule.onNodeWithTag(GROUP_NAME_TAG).getBoundsInRoot().bottom
        assertTrue("field scrolled up from $before to $after", after < before)
        val pickerHeight = assertSheetBelow(GROUP_NAME_TAG)
        assertTrue("picker height $pickerHeight", pickerHeight >= EmojiSheetMinimumPickerHeight - 1.dp)
        captureScreenRoboImage("src/test/snapshots/$name.png")
    }

    private fun openPicker() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.open_emoji_picker)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag(EMOJI_PICKER_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    /** The sheet's top edge (the picker's top minus Material's drag handle) is the gap below the field. */
    private fun assertSheetBelow(fieldTag: String): Dp {
        val field = composeRule.onNodeWithTag(fieldTag).getBoundsInRoot()
        val picker = composeRule.onNodeWithTag(EMOJI_PICKER_TEST_TAG).getBoundsInRoot()
        val sheetTop = picker.top - EmojiSheetDragHandleHeight
        assertTrue("sheet top $sheetTop covers field bottom ${field.bottom}", sheetTop >= field.bottom)
        assertTrue(
            "sheet top $sheetTop leaves unused room below ${field.bottom}",
            sheetTop <= field.bottom + EmojiSheetFieldGap + 1.dp,
        )
        return picker.bottom - picker.top
    }

    private companion object {
        const val GROUP_NAME_TAG = "emoji-sheet.group-name"
        const val PROFILE_NAME = "emoji_sheet_below_notification_profile_name"
        const val GROUP_NAME = "emoji_sheet_below_group_name_scrolled"
    }
}
