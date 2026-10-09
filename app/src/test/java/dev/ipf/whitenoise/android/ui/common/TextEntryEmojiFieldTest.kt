package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
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
    fun stringModelPickerReplacesTheSelectionInsteadOfAppending() {
        var text by mutableStateOf("ABCD")
        composeRule.setContent { WhiteNoiseTheme { ProseTextField(text, { text = it }, Modifier.testTag("field")) } }
        composeRule.onNodeWithTag("field").performTextInputSelection(TextRange(1, 3))
        openPicker()
        pickGrin()
        assertEquals("A😀D", text)
    }

    @Test
    fun externalEditCannotReceiveThePickerResultFromItsPreviousValue() {
        var text by mutableStateOf("old")
        composeRule.setContent { WhiteNoiseTheme { ProseTextField(text, { text = it }, Modifier.testTag("field")) } }
        openPicker()
        composeRule.runOnIdle { text = "new" }
        pickGrin()
        assertEquals("new", text)
        composeRule.onNodeWithTag("field").performTextReplacement("still editable")
        assertEquals("still editable", text)
    }

    @Test
    fun changedOwnerClosesThePickerBeforeItCanEditAReorderedPollOption() {
        var owner by mutableStateOf("first option")
        var text by mutableStateOf("same text")
        composeRule.setContent { WhiteNoiseTheme { ProseTextField(text, { text = it }, emojiOwner = owner) } }
        openPicker()
        composeRule.runOnIdle { owner = "replacement option" }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isEmpty())
        assertEquals("same text", text)
    }

    @Test
    fun becomingDisabledClosesThePickerWithoutChangingText() {
        var enabled by mutableStateOf(true)
        var text by mutableStateOf("draft")
        composeRule.setContent { WhiteNoiseTheme { ProseTextField(text, { text = it }, enabled = enabled) } }
        openPicker()
        composeRule.runOnIdle { enabled = false }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isEmpty())
        assertEquals("draft", text)
    }

    @Test
    fun rejectedOverLimitEmojiLeavesTextAndRecentsUnchanged() {
        var text by mutableStateOf("1234")
        val recents = (context.applicationContext as WhiteNoiseApplication).recentEmojiRecentsOwner
        val before = recents.recents.toList()
        composeRule.setContent { WhiteNoiseTheme { ProseTextField(text, { text = it }, emojiMaxLength = 4) } }
        openPicker()
        pickGrin()
        assertEquals("1234", text)
        assertEquals(before, recents.recents)
    }

    @Test
    fun changingTheDestinationCallbackClosesAnIdenticallyWordedReplacementEditor() {
        var first by mutableStateOf("same")
        var second by mutableStateOf("same")
        var showFirst by mutableStateOf(true)
        val writeFirst: (String) -> Unit = { first = it }
        val writeSecond: (String) -> Unit = { second = it }
        composeRule.setContent {
            WhiteNoiseTheme {
                ProseTextField(if (showFirst) first else second, if (showFirst) writeFirst else writeSecond)
            }
        }
        openPicker()
        composeRule.runOnIdle { showFirst = false }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isEmpty())
        assertEquals("same", first)
        assertEquals("same", second)
    }

    private fun openPicker() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.open_emoji_picker)).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pickGrin() {
        composeRule.onAllNodesWithText("😀")[0].performClick()
    }
}
