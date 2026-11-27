package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.ui.conversation.composer.insertEmojiAtSelection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class TextEntryEmojiFieldTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun stateModelInsertionPreservesGraphemesAndObeysInputLimit() {
        val family = "👨‍👩‍👧"
        val state = TextFieldState("A${family}B")
        val inserted = insertEmojiAtSelection(TextFieldValue(state.text.toString(), TextRange(2, 5)), "😀")
        assertTrue(insertTextEntryEmoji(state, inserted, null))
        assertEquals("A😀B", state.text.toString())
        assertEquals(TextRange(3), state.selection)
        assertFalse(insertTextEntryEmoji(state, TextFieldValue("too long"), InputTransformation.maxLength(4)))
        assertEquals("A😀B", state.text.toString())
    }

    @Test
    fun prosePickerReplacesTheSelectionInsteadOfAppending() {
        val state = TextFieldState("ABCD")
        composeRule.setContent { WhiteNoiseTheme { ProseTextField(state, Modifier.testTag("field")) } }
        composeRule.onNodeWithTag("field").performTextInputSelection(TextRange(1, 3))
        openPicker()
        pickGrin()
        composeRule.waitForIdle()
        assertEquals("A😀D", state.text.toString())
    }

    @Test
    fun externalEditCannotReceiveThePickerResultFromItsPreviousValue() {
        val state = TextFieldState("old")
        composeRule.setContent { WhiteNoiseTheme { ProseTextField(state, Modifier.testTag("field")) } }
        openPicker()
        composeRule.runOnIdle { state.setTextAndPlaceCursorAtEnd("new") }
        pickGrin()
        assertEquals("new", state.text.toString())
        composeRule.onNodeWithTag("field").performTextReplacement("still editable")
        assertEquals("still editable", state.text.toString())
    }

    @Test
    fun becomingDisabledClosesThePickerWithoutChangingText() {
        var enabled by mutableStateOf(true)
        val state = TextFieldState("draft")
        composeRule.setContent { WhiteNoiseTheme { ProseTextField(state, enabled = enabled) } }
        openPicker()
        composeRule.runOnIdle { enabled = false }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isEmpty())
        assertEquals("draft", state.text.toString())
    }

    @Test
    fun rejectedOverLimitEmojiLeavesTextAndRecentsUnchanged() {
        val state = TextFieldState("1234")
        val recents = (context.applicationContext as WhiteNoiseApplication).recentEmojiRecentsOwner
        val before = recents.recents.toList()
        composeRule.setContent { WhiteNoiseTheme { ProseTextField(state, maxLength = 4) } }
        openPicker()
        pickGrin()
        assertEquals("1234", state.text.toString())
        assertEquals(before, recents.recents)
    }

    @Test
    fun replacingTheEditorClosesItsPickerEvenWhenTheTextIsIdentical() {
        val first = TextFieldState("same")
        val second = TextFieldState("same")
        var showFirst by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                ProseTextField(if (showFirst) first else second)
            }
        }
        openPicker()
        composeRule.runOnIdle { showFirst = false }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isEmpty())
        assertEquals("same", first.text.toString())
        assertEquals("same", second.text.toString())
    }

    private fun openPicker() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.open_emoji_picker)).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pickGrin() {
        composeRule.onAllNodesWithText("😀")[0].performClick()
    }
}
