package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.common.GroupNameEmojiField
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GroupNameEmojiFieldScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun newGroupNameLeadingEmojiActionLight() {
        render(
            label = "Group name",
            value = "New community",
            pickerOpen = false,
            darkTheme = false,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/new_group_name_emoji_action_light.png")
    }

    @Test
    fun editGroupNameLeadingEmojiActionSelectedDark() {
        render(
            label = "Group name",
            value = "Marmot team 😀",
            pickerOpen = true,
            darkTheme = true,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/edit_group_name_emoji_action_selected_dark.png")
    }

    private fun render(
        label: String,
        value: String,
        pickerOpen: Boolean,
        darkTheme: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                    GroupNameEmojiField(
                        value = TextFieldValue(value),
                        onValueChange = {},
                        label = label,
                        emojiPickerOpen = pickerOpen,
                        onEmojiPickerClick = {},
                        enabled = true,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "group-name-emoji-field"
    }
}
