package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerEmojiActionContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun composerUsesTheSharedActionAndExposesItsKeyboardToggleState() {
        var pickerOpen by mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ComposerPill(
                        textFieldValue = TextFieldValue("Draft"),
                        composerFocus = FocusRequester(),
                        emojiPickerOpen = pickerOpen,
                        onValueChange = {},
                        onEmojiPickerToggle = { pickerOpen = !pickerOpen },
                        onAttachmentsToggle = {},
                        attachmentSheetOpen = false,
                        onPickFromGallery = null,
                        onPickDocument = null,
                    )
                }
            }
        }

        val openAction = composeRule.onNodeWithContentDescription(string(R.string.open_emoji_picker))
        openAction.assertHasClickAction()
        openAction.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        openAction.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        val bounds = openAction.getUnclippedBoundsInRoot()
        val fieldBounds = composeRule.onNode(hasSetTextAction() and hasText("Draft")).getUnclippedBoundsInRoot()
        assertTrue(
            "Composer emoji action must stay in the leading half of the field",
            bounds.left + bounds.right < fieldBounds.left + fieldBounds.right,
        )
        assertTrue("Composer emoji action width must be at least 48dp", bounds.right - bounds.left >= 48.dp)
        assertTrue("Composer emoji action height must be at least 48dp", bounds.bottom - bounds.top >= 48.dp)

        openAction.performClick()

        composeRule
            .onNodeWithContentDescription(string(R.string.show_keyboard))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    private fun string(res: Int): String = context.getString(res)
}
